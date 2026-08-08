package com.catalogix.cart.controller;

import com.catalogix.cart.dto.*;
import com.catalogix.cart.security.JwtAuthFilter;
import com.catalogix.cart.security.RateLimiterFilter;
import com.catalogix.cart.svc.CartSvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
@WebMvcTest(
        controllers = CartController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class CartControllerTest {

    private static final String TOKEN = "Bearer token";

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private CartSvc svc;

    @Autowired
    private MockMvc mvc;

    private CartResponse emptyCart() {
        return new CartResponse(List.of(), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void getReturnsCurrentCart() throws Exception {
        when(svc.getOrCreateCart(42L, TOKEN)).thenReturn(emptyCart());

        mvc.perform(get("/cart").requestAttr("userId", 42L).requestAttr("bearerToken", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @SuppressWarnings("null")
    void addItemReturnsUpdatedCart() throws Exception {
        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(1L);
        req.setQuantity(2);

        CartItemResponse item = new CartItemResponse(1L, "Phone", 2, new BigDecimal("100.00"),
                new BigDecimal("200.00"), 10);
        CartResponse resp = new CartResponse(List.of(item), null, new BigDecimal("200.00"),
                BigDecimal.ZERO, new BigDecimal("200.00"));
        when(svc.addItem(eq(42L), org.mockito.ArgumentMatchers.any(AddCartItemRequest.class), eq(TOKEN)))
                .thenReturn(resp);

        mvc.perform(post("/cart/items")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("Phone"));
    }

    @Test
    void addItemRejectsMissingProductId() throws Exception {
        AddCartItemRequest req = new AddCartItemRequest();
        req.setQuantity(2);

        mvc.perform(post("/cart/items")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addItemRejectsQuantityBelowOne() throws Exception {
        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(1L);
        req.setQuantity(0);

        mvc.perform(post("/cart/items")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @SuppressWarnings("null")
    void updateItemReturnsUpdatedCart() throws Exception {
        UpdateCartItemRequest req = new UpdateCartItemRequest();
        req.setQuantity(5);

        CartItemResponse item = new CartItemResponse(1L, "Phone", 5, new BigDecimal("100.00"),
                new BigDecimal("500.00"), 10);
        CartResponse resp = new CartResponse(List.of(item), null, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("500.00"));
        when(svc.updateItemQuantity(eq(42L), eq(1L), org.mockito.ArgumentMatchers.any(UpdateCartItemRequest.class), eq(TOKEN)))
                .thenReturn(resp);

        mvc.perform(patch("/cart/items/1")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(5));
    }

    @Test
    void removeItemReturnsUpdatedCart() throws Exception {
        when(svc.removeItem(42L, 1L, TOKEN)).thenReturn(emptyCart());

        mvc.perform(delete("/cart/items/1").requestAttr("userId", 42L).requestAttr("bearerToken", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @SuppressWarnings("null")
    void applyCouponReturnsUpdatedCart() throws Exception {
        CartResponse resp = new CartResponse(List.of(), "SAVE10", new BigDecimal("200.00"),
                new BigDecimal("20.00"), new BigDecimal("180.00"));
        when(svc.applyCoupon(42L, "SAVE10", TOKEN)).thenReturn(resp);

        mvc.perform(post("/cart/coupon")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("code", "SAVE10"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponCode").value("SAVE10"))
                .andExpect(jsonPath("$.discountAmount").value(20.00));

        verify(svc).applyCoupon(42L, "SAVE10", TOKEN);
    }

    @Test
    void removeCouponReturnsUpdatedCart() throws Exception {
        when(svc.removeCoupon(42L, TOKEN)).thenReturn(emptyCart());

        mvc.perform(delete("/cart/coupon").requestAttr("userId", 42L).requestAttr("bearerToken", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponCode").doesNotExist());
    }

    @Test
    void handoffReturnsCartContentsForCheckoutSvc() throws Exception {
        CheckoutHandoff handoff = new CheckoutHandoff(List.of(new CartItemLine(1L, 2)), "SAVE10");
        when(svc.toCheckoutHandoff(42L)).thenReturn(handoff);

        mvc.perform(get("/cart/handoff").requestAttr("userId", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(1L))
                .andExpect(jsonPath("$.couponCode").value("SAVE10"));
    }

    @Test
    void clearReturnsOk() throws Exception {
        mvc.perform(post("/cart/clear").requestAttr("userId", 42L))
                .andExpect(status().isOk());

        verify(svc).clear(42L);
    }
}
