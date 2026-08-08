package com.catalogix.cart.svc;

import com.catalogix.cart.client.CatalogClient;
import com.catalogix.cart.client.InventoryClient;
import com.catalogix.cart.client.ProductInfo;
import com.catalogix.cart.client.PromotionsClient;
import com.catalogix.cart.dto.AddCartItemRequest;
import com.catalogix.cart.dto.CartResponse;
import com.catalogix.cart.dto.CheckoutHandoff;
import com.catalogix.cart.dto.UpdateCartItemRequest;
import com.catalogix.cart.exception.EmptyCartException;
import com.catalogix.cart.exception.ProductUnavailableException;
import com.catalogix.cart.model.Cart;
import com.catalogix.cart.model.CartItem;
import com.catalogix.cart.repository.CartRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CartSvcTest {

    @Mock private CartRepository repo;
    @Mock private CatalogClient catalogClient;
    @Mock private InventoryClient inventoryClient;
    @Mock private PromotionsClient promotionsClient;

    private CartSvc svc;

    private static final String TOKEN = "Bearer test-token";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new CartSvc(repo, catalogClient, inventoryClient, promotionsClient);
        when(repo.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ProductInfo product(Long id, String name, String price) {
        ProductInfo p = new ProductInfo();
        p.setId(id); p.setName(name); p.setPrice(new BigDecimal(price));
        return p;
    }

    // ---- getOrCreateCart ----

    @Test
    void getOrCreateCartCreatesEmptyCartWhenNoneExists() {
        when(repo.findByUserId(42L)).thenReturn(Optional.empty());

        CartResponse resp = svc.getOrCreateCart(42L, TOKEN);

        assertTrue(resp.getItems().isEmpty());
        assertEquals(BigDecimal.ZERO, resp.getSubtotal());
        verify(repo).save(any(Cart.class));
    }

    @Test
    void getOrCreateCartPropagatesWhenProductLookupFails() {
        // Unlike checkout-svc's old (deleted) cart tests, this service does
        // NOT degrade a dead product to a placeholder line — a lookup
        // failure here is a real, unhandled exception. See CartSvc.toResponse.
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(catalogClient.fetch(1L, TOKEN)).thenThrow(new ProductUnavailableException("Product not found: 1"));

        assertThrows(ProductUnavailableException.class, () -> svc.getOrCreateCart(42L, TOKEN));
    }

    // ---- addItem ----

    @Test
    void addItemAddsNewLineAndComputesSubtotal() {
        when(repo.findByUserId(42L)).thenReturn(Optional.empty());
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(10);

        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(1L);
        req.setQuantity(2);

        CartResponse resp = svc.addItem(42L, req, TOKEN);

        assertEquals(1, resp.getItems().size());
        assertEquals(new BigDecimal("200.00"), resp.getSubtotal());
        assertEquals(new BigDecimal("200.00"), resp.getTotal());
        // Fetched once as the up-front "does this product exist" check, once
        // more while rendering the response — see CartSvc.addItem/toResponse.
        verify(catalogClient, times(2)).fetch(1L, TOKEN);
    }

    @Test
    void addItemFailsFastOnUnknownProductWithoutTouchingTheCart() {
        when(catalogClient.fetch(99L, TOKEN)).thenThrow(new ProductUnavailableException("Product not found: 99"));

        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(99L);
        req.setQuantity(1);

        assertThrows(ProductUnavailableException.class, () -> svc.addItem(42L, req, TOKEN));
        verify(repo, never()).save(any());
    }

    @Test
    void addItemIncrementsQuantityWhenProductAlreadyInCart() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(10);

        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(1L);
        req.setQuantity(3);

        CartResponse resp = svc.addItem(42L, req, TOKEN);

        assertEquals(1, resp.getItems().size());
        assertEquals(5, resp.getItems().get(0).getQuantity());
    }

    // ---- updateItemQuantity ----

    @Test
    void updateItemQuantityChangesExistingLine() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(10);

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

        // The real exception here is EmptyCartException (its message just
        // happens to say "Product not in cart") — there's no separate
        // IllegalArgumentException path in this service.
        assertThrows(EmptyCartException.class, () -> svc.updateItemQuantity(42L, 99L, req, TOKEN));
    }

    @Test
    void updateItemQuantityRejectsWhenCartDoesNotExistAtAll() {
        when(repo.findByUserId(42L)).thenReturn(Optional.empty());

        UpdateCartItemRequest req = new UpdateCartItemRequest();
        req.setQuantity(5);

        assertThrows(EmptyCartException.class, () -> svc.updateItemQuantity(42L, 1L, req, TOKEN));
    }

    // ---- removeItem ----

    @Test
    void removeItemRemovesTheLine() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));

        CartResponse resp = svc.removeItem(42L, 1L, TOKEN);

        assertTrue(resp.getItems().isEmpty());
        verifyNoInteractions(catalogClient);
    }

    @Test
    void removeItemRejectsWhenCartDoesNotExist() {
        when(repo.findByUserId(42L)).thenReturn(Optional.empty());
        assertThrows(EmptyCartException.class, () -> svc.removeItem(42L, 1L, TOKEN));
    }

    // ---- applyCoupon / removeCoupon ----

    @Test
    void applyCouponAppliesDiscountAndUppercasesCode() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        // preview() is called twice: once in applyCoupon itself against the
        // code exactly as passed in ("save10"), then again inside the
        // toResponse() it calls afterwards, by which point the code on the
        // entity has already been uppercased to "SAVE10" — see CartSvc.applyCoupon.
        when(promotionsClient.preview(eq("save10"), eq(new BigDecimal("200.00")), eq(TOKEN)))
                .thenReturn(new BigDecimal("20.00"));
        when(promotionsClient.preview(eq("SAVE10"), eq(new BigDecimal("200.00")), eq(TOKEN)))
                .thenReturn(new BigDecimal("20.00"));

        CartResponse resp = svc.applyCoupon(42L, "save10", TOKEN);

        assertEquals("SAVE10", cart.getCouponCode());
        assertEquals(new BigDecimal("20.00"), resp.getDiscountAmount());
        assertEquals(new BigDecimal("180.00"), resp.getTotal());
    }

    @Test
    void applyCouponRejectsInvalidCodeAndLeavesCartUntouched() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 1));
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(promotionsClient.preview(eq("BAD"), any(), eq(TOKEN)))
                .thenThrow(new ProductUnavailableException("Coupon is not valid: BAD"));

        assertThrows(ProductUnavailableException.class, () -> svc.applyCoupon(42L, "BAD", TOKEN));
        // setCouponCode only runs after preview() succeeds, so a rejected
        // coupon never gets persisted onto the cart in the first place.
        assertNull(cart.getCouponCode());
    }

    @Test
    void removeCouponClearsTheCode() {
        Cart cart = new Cart(42L);
        cart.setCouponCode("SAVE10");
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));

        svc.removeCoupon(42L, TOKEN);

        assertNull(cart.getCouponCode());
    }

    @Test
    void renderingDropsACouponThatWentInvalidSinceItWasApplied() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        cart.setCouponCode("SAVE10");
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(10);
        when(promotionsClient.preview(eq("SAVE10"), any(), eq(TOKEN)))
                .thenThrow(new ProductUnavailableException("Coupon is not valid: SAVE10"));

        CartResponse resp = svc.getOrCreateCart(42L, TOKEN);

        assertNull(resp.getCouponCode());
        assertNull(cart.getCouponCode());
        assertEquals(BigDecimal.ZERO, resp.getDiscountAmount());
    }

    // ---- toCheckoutHandoff ----

    @Test
    void toCheckoutHandoffCarriesItemsAndCouponCode() {
        Cart cart = new Cart(42L);
        cart.addItem(new CartItem(1L, 2));
        cart.setCouponCode("SAVE10");
        when(repo.findByUserId(42L)).thenReturn(Optional.of(cart));

        CheckoutHandoff handoff = svc.toCheckoutHandoff(42L);

        assertEquals(1, handoff.getItems().size());
        assertEquals(1L, handoff.getItems().get(0).getProductId());
        assertEquals(2, handoff.getItems().get(0).getQuantity());
        assertEquals("SAVE10", handoff.getCouponCode());
    }

    @Test
    void toCheckoutHandoffRejectsWhenCartDoesNotExist() {
        when(repo.findByUserId(42L)).thenReturn(Optional.empty());
        assertThrows(EmptyCartException.class, () -> svc.toCheckoutHandoff(42L));
    }

    @Test
    void toCheckoutHandoffRejectsAnEmptyCart() {
        when(repo.findByUserId(42L)).thenReturn(Optional.of(new Cart(42L)));
        assertThrows(EmptyCartException.class, () -> svc.toCheckoutHandoff(42L));
    }

    // ---- clear ----

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

    @Test
    void clearIsANoOpWhenCartDoesNotExist() {
        when(repo.findByUserId(42L)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> svc.clear(42L));
        verify(repo, never()).save(any());
    }
}
