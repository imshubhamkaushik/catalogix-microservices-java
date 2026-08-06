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
import org.mockito.InjectMocks;
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

    @InjectMocks private CartSvc svc;

    private static final String TOKEN = "Bearer test-token";
    private static final String PHONE = "Phone";
    private static final String PRICE_100 = "100.00";
    private static final String PRICE_200 = "200.00";
    private static final String COUPON_SAVE10 = "SAVE10";
    private static final String BAD_COUPON = "BAD";
    private static final Long USER_ID = 42L;
    private static final Long PRODUCT_ID = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(repo.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ProductLookupResponse product(Long id, String name, String price, int stock) {
        ProductLookupResponse p = new ProductLookupResponse();
        p.setId(id); p.setName(name); p.setPrice(new BigDecimal(price)); p.setStockQuantity(stock);
        return p;
    }

    @Test
    void getOrCreateCartCreatesEmptyCartWhenNoneExists() {
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.empty());

        CartResponse resp = svc.getOrCreateCart(USER_ID, TOKEN);

        assertTrue(resp.getItems().isEmpty());
        assertEquals(BigDecimal.ZERO, resp.getSubtotal());
        verify(repo).save(any(Cart.class));
    }

    @Test
    void addItemAddsNewLineAndComputesSubtotal() {
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(productSvcClient.fetchProduct(PRODUCT_ID, TOKEN)).thenReturn(product(PRODUCT_ID, PHONE, PRICE_100, 10));

        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(PRODUCT_ID);
        req.setQuantity(2);

        CartResponse resp = svc.addItem(USER_ID, req, TOKEN);

        assertEquals(1, resp.getItems().size());
        assertEquals(new BigDecimal(PRICE_200), resp.getSubtotal());
        assertEquals(new BigDecimal(PRICE_200), resp.getTotal());
    }

    @Test
    void addItemIncrementsQuantityWhenProductAlreadyInCart() {
        Cart cart = new Cart(USER_ID);
        cart.addItem(new CartItem(PRODUCT_ID, 2));
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(productSvcClient.fetchProduct(PRODUCT_ID, TOKEN)).thenReturn(product(PRODUCT_ID, PHONE, PRICE_100, 10));

        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(PRODUCT_ID);
        req.setQuantity(3);

        CartResponse resp = svc.addItem(USER_ID, req, TOKEN);

        assertEquals(1, resp.getItems().size());
        assertEquals(5, resp.getItems().get(0).getQuantity());
    }

    @Test
    void updateItemQuantityChangesExistingLine() {
        Cart cart = new Cart(USER_ID);
        cart.addItem(new CartItem(PRODUCT_ID, 2));
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(productSvcClient.fetchProduct(PRODUCT_ID, TOKEN)).thenReturn(product(PRODUCT_ID, PHONE, PRICE_100, 10));

        UpdateCartItemRequest req = new UpdateCartItemRequest();
        req.setQuantity(5);

        CartResponse resp = svc.updateItemQuantity(USER_ID, PRODUCT_ID, req, TOKEN);

        assertEquals(5, resp.getItems().get(0).getQuantity());
    }

    @Test
    void updateItemQuantityRejectsProductNotInCart() {
        Cart cart = new Cart(USER_ID);
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        UpdateCartItemRequest req = new UpdateCartItemRequest();
        req.setQuantity(5);

        assertThrows(IllegalArgumentException.class, () -> svc.updateItemQuantity(USER_ID, 99L, req, TOKEN));
    }

    @Test
    void removeItemRemovesTheLine() {
        Cart cart = new Cart(USER_ID);
        cart.addItem(new CartItem(PRODUCT_ID, 2));
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        CartResponse resp = svc.removeItem(USER_ID, PRODUCT_ID, TOKEN);

        assertTrue(resp.getItems().isEmpty());
        verifyNoInteractions(productSvcClient);
    }

    @Test
    void cartShowsUnavailableItemGracefullyWhenProductLookupFails() {
        Cart cart = new Cart(USER_ID);
        cart.addItem(new CartItem(PRODUCT_ID, 2));
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(productSvcClient.fetchProduct(PRODUCT_ID, TOKEN))
                .thenThrow(new com.catalogix.order.exception.ProductUnavailableException("Product not found: 1"));

        CartResponse resp = svc.getOrCreateCart(USER_ID, TOKEN);

        assertEquals(1, resp.getItems().size());
        assertEquals("This product is no longer available", resp.getItems().get(0).getProductName());
        assertEquals(BigDecimal.ZERO, resp.getSubtotal());
    }

    @Test
    void applyCouponRejectsInvalidCode() {
        Cart cart = new Cart(USER_ID);
        cart.addItem(new CartItem(PRODUCT_ID, 1));
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(couponSvc.validate(BAD_COUPON)).thenThrow(new CouponInvalidException("Coupon code not found: " + BAD_COUPON));

        ApplyCouponRequest req = new ApplyCouponRequest();
        req.setCode(BAD_COUPON);

        assertThrows(CouponInvalidException.class, () -> svc.applyCoupon(USER_ID, req, TOKEN));
        assertNull(cart.getCouponCode());
    }

    @Test
    void applyCouponAppliesDiscountToCartTotal() {
        Cart cart = new Cart(USER_ID);
        cart.addItem(new CartItem(PRODUCT_ID, 2));
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(productSvcClient.fetchProduct(PRODUCT_ID, TOKEN)).thenReturn(product(PRODUCT_ID, PHONE, PRICE_100, 10));

        Coupon coupon = new Coupon();
        coupon.setCode(COUPON_SAVE10);
        coupon.setDiscountType(DiscountType.PERCENTAGE);
        coupon.setDiscountValue(BigDecimal.TEN);
        when(couponSvc.validate(COUPON_SAVE10)).thenReturn(coupon);
        when(couponSvc.calculateDiscount(coupon, new BigDecimal(PRICE_200))).thenReturn(new BigDecimal("20.00"));

        ApplyCouponRequest req = new ApplyCouponRequest();
        req.setCode(COUPON_SAVE10);

        CartResponse resp = svc.applyCoupon(USER_ID, req, TOKEN);

        assertEquals(COUPON_SAVE10, resp.getCouponCode());
        assertEquals(new BigDecimal("20.00"), resp.getDiscountAmount());
        assertEquals(new BigDecimal("180.00"), resp.getTotal());
    }

    @Test
    void toOrderRequestRejectsEmptyCart() {
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.of(new Cart(USER_ID)));
        assertThrows(IllegalArgumentException.class, () -> svc.toOrderRequest(USER_ID));
    }

    @Test
    void toOrderRequestCarriesItemsAndCouponCode() {
        Cart cart = new Cart(USER_ID);
        cart.addItem(new CartItem(PRODUCT_ID, 2));
        cart.setCouponCode(COUPON_SAVE10);
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        CreateOrderRequest req = svc.toOrderRequest(USER_ID);

        assertEquals(1, req.getItems().size());
        assertEquals(PRODUCT_ID, req.getItems().get(0).getProductId());
        assertEquals(2, req.getItems().get(0).getQuantity());
        assertEquals(COUPON_SAVE10, req.getCouponCode());
    }

    @Test
    void clearEmptiesItemsAndCouponCode() {
        Cart cart = new Cart(USER_ID);
        cart.addItem(new CartItem(PRODUCT_ID, 2));
        cart.setCouponCode(COUPON_SAVE10);
        when(repo.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        svc.clear(USER_ID);

        assertTrue(cart.getItems().isEmpty());
        assertNull(cart.getCouponCode());
        verify(repo).save(cart);
    }
}