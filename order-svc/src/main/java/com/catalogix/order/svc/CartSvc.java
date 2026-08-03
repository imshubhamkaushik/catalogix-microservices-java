package com.catalogix.order.svc;

import com.catalogix.order.client.ProductSvcClient;
import com.catalogix.order.dto.*;
import com.catalogix.order.exception.CouponInvalidException;
import com.catalogix.order.model.Cart;
import com.catalogix.order.model.CartItem;
import com.catalogix.order.model.Coupon;
import com.catalogix.order.repository.CartRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A persistent, server-side cart — one per user (survives a page refresh or
 * switching devices, unlike a client-only cart kept in React state). Cart
 * items are priced *live* from product-svc every time the cart is viewed
 * (unlike OrderItem, which snapshots price at the moment of purchase);
 * checking out hands the cart's contents to OrderSvc.createOrder, which does
 * the actual (snapshotted, stock-reserving) order creation.
 */
@Service
public class CartSvc {

    private final CartRepository repo;
    private final ProductSvcClient productSvcClient;
    private final CouponSvc couponSvc;

    public CartSvc(CartRepository repo, ProductSvcClient productSvcClient, CouponSvc couponSvc) {
        this.repo = repo;
        this.productSvcClient = productSvcClient;
        this.couponSvc = couponSvc;
    }

    @Transactional
    public CartResponse getOrCreateCart(Long userId, String bearerToken) {
        Cart cart = repo.findByUserId(userId).orElseGet(() -> repo.save(new Cart(userId)));
        return toResponse(cart, bearerToken);
    }

    @Transactional
    public CartResponse addItem(Long userId, AddCartItemRequest req, String bearerToken) {
        Cart cart = repo.findByUserId(userId).orElseGet(() -> new Cart(userId));
        Optional<CartItem> existing = cart.getItems().stream()
                .filter(i -> i.getProductId().equals(req.getProductId()))
                .findFirst();

        if (existing.isPresent()) {
            existing.get().setQuantity(existing.get().getQuantity() + req.getQuantity());
        } else {
            cart.addItem(new CartItem(req.getProductId(), req.getQuantity()));
        }
        cart.setUpdatedAt(Instant.now());
        return toResponse(repo.save(cart), bearerToken);
    }

    @Transactional
    public CartResponse updateItemQuantity(Long userId, Long productId, UpdateCartItemRequest req, String bearerToken) {
        Cart cart = findCartOrThrow(userId);
        CartItem item = cart.getItems().stream()
                .filter(i -> i.getProductId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Product " + productId + " is not in your cart"));
        item.setQuantity(req.getQuantity());
        cart.setUpdatedAt(Instant.now());
        return toResponse(repo.save(cart), bearerToken);
    }

    @Transactional
    public CartResponse removeItem(Long userId, Long productId, String bearerToken) {
        Cart cart = findCartOrThrow(userId);
        cart.removeItemByProductId(productId);
        cart.setUpdatedAt(Instant.now());
        return toResponse(repo.save(cart), bearerToken);
    }

    @Transactional
    public CartResponse applyCoupon(Long userId, ApplyCouponRequest req, String bearerToken) {
        Cart cart = findCartOrThrow(userId);
        couponSvc.validate(req.getCode()); // throws CouponInvalidException if not currently redeemable
        cart.setCouponCode(req.getCode().toUpperCase());
        cart.setUpdatedAt(Instant.now());
        return toResponse(repo.save(cart), bearerToken);
    }

    @Transactional
    public CartResponse removeCoupon(Long userId, String bearerToken) {
        Cart cart = findCartOrThrow(userId);
        cart.setCouponCode(null);
        cart.setUpdatedAt(Instant.now());
        return toResponse(repo.save(cart), bearerToken);
    }

    @Transactional
    public void clear(Long userId) {
        repo.findByUserId(userId).ifPresent(cart -> {
            cart.getItems().clear();
            cart.setCouponCode(null);
            cart.setUpdatedAt(Instant.now());
            repo.save(cart);
        });
    }

    // Builds the request OrderSvc.createOrder needs from the cart's current
    // contents — the actual stock reservation/pricing snapshot happens there.
    @Transactional(readOnly = true)
    public CreateOrderRequest toOrderRequest(Long userId) {
        Cart cart = findCartOrThrow(userId);
        if (cart.getItems().isEmpty()) {
            throw new IllegalArgumentException("Your cart is empty");
        }
        CreateOrderRequest req = new CreateOrderRequest();
        req.setItems(cart.getItems().stream().map(i -> {
            OrderItemRequest itemReq = new OrderItemRequest();
            itemReq.setProductId(i.getProductId());
            itemReq.setQuantity(i.getQuantity());
            return itemReq;
        }).toList());
        req.setCouponCode(cart.getCouponCode());
        return req;
    }

    private Cart findCartOrThrow(Long userId) {
        return repo.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Your cart is empty"));
    }

    private CartResponse toResponse(Cart cart, String bearerToken) {
        List<CartItemResponse> itemResponses = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (CartItem item : cart.getItems()) {
            try {
                ProductLookupResponse product = productSvcClient.fetchProduct(item.getProductId(), bearerToken);
                BigDecimal lineSubtotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                itemResponses.add(new CartItemResponse(
                        item.getProductId(), product.getName(), item.getQuantity(),
                        product.getPrice(), lineSubtotal, product.getStockQuantity()));
                subtotal = subtotal.add(lineSubtotal);
            } catch (RuntimeException e) {
                // Product was deleted (or product-svc is briefly unreachable) since this was
                // added to the cart — surface it as unavailable rather than failing the whole
                // cart view. The user can remove it; checkout would fail on it anyway.
                itemResponses.add(new CartItemResponse(
                        item.getProductId(), "This product is no longer available",
                        item.getQuantity(), BigDecimal.ZERO, BigDecimal.ZERO, 0));
            }
        }

        BigDecimal discount = BigDecimal.ZERO;
        if (cart.getCouponCode() != null) {
            try {
                Coupon coupon = couponSvc.validate(cart.getCouponCode());
                discount = couponSvc.calculateDiscount(coupon, subtotal);
            } catch (CouponInvalidException e) {
                // The coupon was valid when applied but no longer is (expired, deactivated, or
                // exhausted by someone else since) — show no discount rather than erroring the
                // whole cart view; checkout will surface the same CouponInvalidException clearly.
                discount = BigDecimal.ZERO;
            }
        }

        BigDecimal total = subtotal.subtract(discount);
        return new CartResponse(itemResponses, cart.getCouponCode(), subtotal, discount, total);
    }
}
