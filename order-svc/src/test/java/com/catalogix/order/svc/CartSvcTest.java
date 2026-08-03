package com.catalogix.order.svc;

import com.catalogix.order.client.ProductSvcClient;
import com.catalogix.order.dto.*;
import com.catalogix.order.exception.CouponInvalidException;
import com.catalogix.order.model.Cart;
import com.catalogix.order.model.CartItem;
import com.catalogix.order.model.Coupon;
import com.catalogix.order.model.DiscountType;
import com.catalogix.order.repository.CartRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("null")
class CartSvcTest {

    @Mock private CartRepository repo;
    @Mock private ProductSvcClient productSvcClient;
    @Mock private CouponSvc couponSvc;

    private CartSvc svc;

    private static final String TOKEN = "Bearer test-token";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new CartSvc(repo, productSvcClient, couponSvc);
        when(repo.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ProductLookupResponse product(Long id, String name, String price, int stock) {
        ProductLookupResponse p = new ProductLookupResponse();
        p.setId(id); p.setName(name); p.setPrice(new BigDecimal(price)); p.setStockQuantity(stock);
        return p;
    }

    @Test
    void getOrCreateCartCreatesEmptyCartWhenNoneExists() {
        when(repo.findByUserId(42L)).thenReturn(Optional.empty());

        CartResponse resp = svc.getOrCreateCart(42L, TOKEN);

        assertTrue(resp.getItems().isEmpty());
        assertEquals(BigDecimal.ZERO, resp.getSubtotal());
        verify(repo).save(any(Cart.class));
    }

    @Test
    void addItemAddsNewLineAndComputesSubtotal() {
        when(repo.findByUserId(42L)).thenReturn(Optional.empty());
        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 10));

        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(1L);
        req.setQuantity(2);

        CartResponse resp = svc.addItem(42L, req, TOKEN);

        assertEquals(1, resp.getItems().size());
        assertEquals(new BigDecimal("200.00"), resp.getSubtotal());
        assertEquals(new BigDecimal("200.00"), resp.getTotal());
    }

    @Test
    void addItemIncrementsQuantityWhenProductAlreadyInCart() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 10));

        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(1L);
        req.setQuantity(3);

        CartResponse resp = svc.addItem(42L, req, TOKEN);

        assertEquals(1, resp.getItems().size());
        assertEquals(5, resp.getItems().get(0).getQuantity());
    }

    @Test
    void updateItemQuantityChangesExistingLine() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 10));

        UpdateCartItemRequest req = new UpdateCartItemRequest();
        req.setQuantity(5);

        CartResponse resp = svc.updateItemQuantity(42L, 1L, req, TOKEN);

        assertEquals(5, resp.getItems().get(0).getQuantity());
    }

    @Test
    void updateItemQuantityRejectsProductNotInCart() {
        Cart cart = new Cart(42L);
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));

        UpdateCartItemRequest req = new UpdateCartItemRequest();
        req.setQuantity(5);

        assertThrows(IllegalArgumentException.class, () -> svc.updateItemQuantity(42L, 99L, req, TOKEN));
    }

    @Test
    void removeItemRemovesTheLine() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));

        CartResponse resp = svc.removeItem(42L, 1L, TOKEN);

        assertTrue(resp.getItems().isEmpty());
        verifyNoInteractions(productSvcClient);
    }

    @Test
    void cartShowsUnavailableItemGracefullyWhenProductLookupFails() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(productSvcClient.fetchProduct(1L, TOKEN))
                .thenThrow(new com.catalogix.order.exception.ProductUnavailableException("Product not found: 1"));

        CartResponse resp = svc.getOrCreateCart(42L, TOKEN);

        assertEquals(1, resp.getItems().size());
        assertEquals("This product is no longer available", resp.getItems().get(0).getProductName());
        assertEquals(BigDecimal.ZERO, resp.getSubtotal());
    }

    @Test
    void applyCouponRejectsInvalidCode() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 1));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(couponSvc.validate("BAD")).thenThrow(new CouponInvalidException("Coupon code not found: BAD"));

        ApplyCouponRequest req = new ApplyCouponRequest();
        req.setCode("BAD");

        assertThrows(CouponInvalidException.class, () -> svc.applyCoupon(42L, req, TOKEN));
        assertNull(cart.getCouponCode());
    }

    @Test
    void applyCouponAppliesDiscountToCartTotal() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 10));

        Coupon coupon = new Coupon();
        coupon.setCode("SAVE10");
        coupon.setDiscountType(DiscountType.PERCENTAGE);
        coupon.setDiscountValue(BigDecimal.TEN);
        when(couponSvc.validate("SAVE10")).thenReturn(coupon);
        when(couponSvc.calculateDiscount(coupon, new BigDecimal("200.00"))).thenReturn(new BigDecimal("20.00"));

        ApplyCouponRequest req = new ApplyCouponRequest();
        req.setCode("SAVE10");

        CartResponse resp = svc.applyCoupon(42L, req, TOKEN);

        assertEquals("SAVE10", resp.getCouponCode());
        assertEquals(new BigDecimal("20.00"), resp.getDiscountAmount());
        assertEquals(new BigDecimal("180.00"), resp.getTotal());
    }

    @Test
    void toOrderRequestRejectsEmptyCart() {
        when(repo.findByUserId(42L)).thenReturn(Optional.of(new Cart(42L)));
        assertThrows(IllegalArgumentException.class, () -> svc.toOrderRequest(42L));
    }

    @Test
    void toOrderRequestCarriesItemsAndCouponCode() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        cart.setCouponCode("SAVE10");
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));

        CreateOrderRequest req = svc.toOrderRequest(42L);

        assertEquals(1, req.getItems().size());
        assertEquals(1L, req.getItems().get(0).getProductId());
        assertEquals(2, req.getItems().get(0).getQuantity());
        assertEquals("SAVE10", req.getCouponCode());
    }

    @Test
    void clearEmptiesItemsAndCouponCode() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        cart.setCouponCode("SAVE10");
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));

        svc.clear(42L);

        assertTrue(cart.getItems().isEmpty());
        assertNull(cart.getCouponCode());
        verify(repo).save(cart);
    }
}
