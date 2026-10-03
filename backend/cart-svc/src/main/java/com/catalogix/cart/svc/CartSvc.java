package com.catalogix.cart.svc;

import com.catalogix.cart.client.CatalogClient;
import com.catalogix.cart.client.InventoryClient;
import com.catalogix.cart.client.ProductInfo;
import com.catalogix.cart.dto.*;
import com.catalogix.cart.exception.EmptyCartException;
import com.catalogix.cart.model.Cart;
import com.catalogix.cart.model.CartItem;
import com.catalogix.cart.repository.CartRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class CartSvc {

    private static final Logger log = LoggerFactory.getLogger(CartSvc.class);

    private final CartRepository repo;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private static final String CART_IS_EMPTY = "Cart is empty";

    public CartSvc(CartRepository repo, CatalogClient catalogClient,
                   InventoryClient inventoryClient) {
        this.repo = repo;
        this.catalogClient = catalogClient;
        this.inventoryClient = inventoryClient;
    }

    @Transactional
    public CartResponse getOrCreateCart(Long userId, String bearerToken) {
        Cart cart = repo.findByUserId(userId).orElseGet(() -> repo.save(new Cart(userId)));
        return toResponse(cart, bearerToken);
    }

    @Transactional
    public CartResponse addItem(Long userId, AddCartItemRequest req, String bearerToken) {
        // Fetches product up front so a bad productId fails fast with a
        // clear error, rather than silently adding a dangling line.
        catalogClient.fetch(req.getProductId(), bearerToken);

        Cart cart = repo.findByUserId(userId).orElseGet(() -> new Cart(userId));
        CartItem existing = cart.getItems().stream()
                .filter(i -> i.getProductId().equals(req.getProductId()))
                .findFirst().orElse(null);

        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + req.getQuantity());
        } else {
            cart.addItem(new CartItem(req.getProductId(), req.getQuantity()));
        }
        cart.setUpdatedAt(Instant.now());
        Cart saved = repo.save(cart);
        return toResponse(saved, bearerToken);
    }

    @Transactional
    public CartResponse updateItemQuantity(Long userId, Long productId, UpdateCartItemRequest req, String bearerToken) {
        Cart cart = repo.findByUserId(userId)
                .orElseThrow(() -> new EmptyCartException(CART_IS_EMPTY));
        CartItem item = cart.getItems().stream()
                .filter(i -> i.getProductId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new EmptyCartException("Product not in cart: " + productId));
        item.setQuantity(req.getQuantity());
        cart.setUpdatedAt(Instant.now());
        Cart saved = repo.save(cart);
        return toResponse(saved, bearerToken);
    }

    @Transactional
    public CartResponse removeItem(Long userId, Long productId, String bearerToken) {
        Cart cart = repo.findByUserId(userId)
                .orElseThrow(() -> new EmptyCartException(CART_IS_EMPTY ));
        cart.removeItemByProductId(productId);
        cart.setUpdatedAt(Instant.now());
        Cart saved = repo.save(cart);
        return toResponse(saved, bearerToken);
    }


    /**
     * Handed to checkout-svc when the user checks out. Deliberately just
     * product/quantity pairs — checkout-svc re-derives price and re-reserves
     * stock itself rather than trusting a snapshot
     * cart-svc computed possibly seconds earlier, exactly as if the browser
     * had posted the same payload straight to POST /orders.
     */
    @Transactional(readOnly = true)
    public CheckoutHandoff toCheckoutHandoff(Long userId) {
        Cart cart = repo.findByUserId(userId)
                .orElseThrow(() -> new EmptyCartException(CART_IS_EMPTY));
        if (cart.getItems().isEmpty()) {
            throw new EmptyCartException("Cannot checkout an empty cart");
        }
        List<CartItemLine> lines = cart.getItems().stream()
                .map(i -> new CartItemLine(i.getProductId(), i.getQuantity()))
                .toList();
        return new CheckoutHandoff(lines);
    }

    // Called by checkout-svc after it has successfully created the order —
    // clears the cart the same way the original CartController.checkout did in-process. 
    // If this call fails (network blip) the order still exists; the user just sees stale cart contents until they refresh, 
    // which is a display-only inconsistency, not a lost/duplicated order or charge.
    @Transactional
    public void clear(Long userId) {
        repo.findByUserId(userId).ifPresent(cart -> {
            cart.getItems().clear();
            cart.setUpdatedAt(Instant.now());
            repo.save(cart);
        });
    }


    private CartResponse toResponse(Cart cart, String bearerToken) {
        List<CartItemResponse> items = cart.getItems().stream().map(item -> {
            ProductInfo info = catalogClient.fetch(item.getProductId(), bearerToken);
            Integer stock = inventoryClient.fetchQuantity(item.getProductId(), bearerToken);
            BigDecimal lineSubtotal = info.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            return new CartItemResponse(item.getProductId(), info.getName(), item.getQuantity(),
                    info.getPrice(), lineSubtotal, stock);
        }).toList();

        BigDecimal subtotal = items.stream().map(CartItemResponse::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartResponse(items, subtotal, subtotal);
    }
}
