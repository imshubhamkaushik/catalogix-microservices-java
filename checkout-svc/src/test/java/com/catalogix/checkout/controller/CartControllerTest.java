package com.catalogix.checkout.controller;

import com.catalogix.checkout.dto.*;
import com.catalogix.checkout.security.JwtAuthFilter;
import com.catalogix.checkout.security.RateLimiterFilter;
import com.catalogix.checkout.svc.CartSvc;
import com.catalogix.checkout.svc.OrderSvc;
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
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = CartController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class CartControllerTest {

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private CartSvc cartSvc;

    @MockitoBean
    private OrderSvc orderSvc;

    @Autowired
    private MockMvc mvc;

    @Test
    void getCartReturnsCurrentCart() throws Exception {
        when(cartSvc.getOrCreateCart(42L, "Bearer token"))
                .thenReturn(new CartResponse(Collections.emptyList(), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        mvc.perform(get("/cart").requestAttr("userId", 42L).requestAttr("bearerToken", "Bearer token"))
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
        when(cartSvc.addItem(eq(42L), any(AddCartItemRequest.class), eq("Bearer token")))
                .thenReturn(new CartResponse(List.of(item), null, new BigDecimal("200.00"), BigDecimal.ZERO, new BigDecimal("200.00")));

        mvc.perform(post("/cart/items")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("Phone"));
    }

    @Test
    @SuppressWarnings("null")
    void checkoutCreatesOrderAndClearsCartWhenNew() throws Exception {
        CreateOrderRequest orderReq = new CreateOrderRequest();
        OrderItemRequest itemReq = new OrderItemRequest();
        itemReq.setProductId(1L);
        itemReq.setQuantity(2);
        orderReq.setItems(List.of(itemReq));
        when(cartSvc.toOrderRequest(42L)).thenReturn(orderReq);

        OrderItemResponse itemResp = new OrderItemResponse(1L, "Phone", 2, new BigDecimal("100.00"), new BigDecimal("200.00"));
        OrderResponse orderResp = new OrderResponse(7L, 42L, com.catalogix.checkout.model.OrderStatus.PENDING_PAYMENT,
                new BigDecimal("200.00"), Instant.now(), List.of(itemResp));
        when(orderSvc.createOrder(eq(42L), eq(orderReq), eq("Bearer token"), any(), eq("buyer@example.com")))
                .thenReturn(new OrderSvc.OrderCreationResult(orderResp, true));

        mvc.perform(post("/cart/checkout")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", "Bearer token")
                .requestAttr("userEmail", "buyer@example.com"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7L));

        org.mockito.Mockito.verify(cartSvc).clear(42L);
    }
}
