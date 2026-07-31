package com.catalogix.order.controller;

import com.catalogix.order.dto.*;
import com.catalogix.order.exception.ForbiddenException;
import com.catalogix.order.exception.ProductUnavailableException;
import com.catalogix.order.model.OrderStatus;
import com.catalogix.order.security.JwtAuthFilter;
import com.catalogix.order.security.RateLimiterFilter;
import com.catalogix.order.svc.OrderSvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
@WebMvcTest(
        controllers = OrderController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class OrderControllerTest {

    private static final String EMAIL = "buyer@example.com";

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private OrderSvc svc;

    @Autowired
    private MockMvc mvc;

    private CreateOrderRequest sampleRequest() {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(1L);
        item.setQuantity(2);
        CreateOrderRequest req = new CreateOrderRequest();
        req.setItems(List.of(item));
        return req;
    }

    private OrderResponse sampleResponse() {
        OrderItemResponse item = new OrderItemResponse(
                1L, "Phone", 2, new BigDecimal("100.00"), new BigDecimal("200.00"));
        return new OrderResponse(1L, 42L, OrderStatus.CONFIRMED, new BigDecimal("200.00"),
                Instant.now(), List.of(item));
    }

    @Test
    @SuppressWarnings("null")
    void createReturnsCreatedWhenNewOrder() throws Exception {
        when(svc.createOrder(eq(42L), any(CreateOrderRequest.class), eq("Bearer token"), isNull(), eq(EMAIL)))
                .thenReturn(new OrderSvc.OrderCreationResult(sampleResponse(), true));

        mvc.perform(post("/orders")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", "Bearer token")
                .requestAttr("userEmail", EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    @SuppressWarnings("null")
    void createReturnsOkNotCreatedWhenIdempotencyKeyMatchesExistingOrder() throws Exception {
        when(svc.createOrder(eq(42L), any(CreateOrderRequest.class), eq("Bearer token"), eq("key-abc"), eq(EMAIL)))
                .thenReturn(new OrderSvc.OrderCreationResult(sampleResponse(), false));

        mvc.perform(post("/orders")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", "Bearer token")
                .requestAttr("userEmail", EMAIL)
                .header("Idempotency-Key", "key-abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    @SuppressWarnings("null")
    void createReturnsConflictWhenProductUnavailable() throws Exception {
        when(svc.createOrder(eq(42L), any(CreateOrderRequest.class), eq("Bearer token"), isNull(), eq(EMAIL)))
                .thenThrow(new ProductUnavailableException("Insufficient stock for product 1"));

        mvc.perform(post("/orders")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", "Bearer token")
                .requestAttr("userEmail", EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    @SuppressWarnings("null")
    void createRecoversFromIdempotencyKeyRaceByReturningWinningOrder() throws Exception {
        when(svc.createOrder(eq(42L), any(CreateOrderRequest.class), eq("Bearer token"), eq("key-abc"), eq(EMAIL)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(svc.findExistingByIdempotencyKey(42L, "key-abc"))
                .thenReturn(Optional.of(sampleResponse()));

        mvc.perform(post("/orders")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", "Bearer token")
                .requestAttr("userEmail", EMAIL)
                .header("Idempotency-Key", "key-abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    void listReturnsOwnOrdersForRegularUser() throws Exception {
        PagedResponse<OrderResponse> page = new PagedResponse<>(List.of(sampleResponse()), 0, 20, 1, 1);
        when(svc.listOrders(eq(42L), eq("USER"), any(Pageable.class))).thenReturn(page);

        mvc.perform(get("/orders").requestAttr("userId", 42L).requestAttr("userRole", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", Objects.requireNonNull(hasSize(1))));
    }

    @Test
    @SuppressWarnings("null")
    void getOneReturnsForbiddenWhenNotOwner() throws Exception {
        when(svc.getOrder(1L, 99L, "USER"))
                .thenThrow(new ForbiddenException("You may only view or manage your own orders"));

        mvc.perform(get("/orders/1").requestAttr("userId", 99L).requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("null")
    void cancelReturnsOk() throws Exception {
        OrderResponse cancelled = sampleResponse();
        cancelled.setStatus(OrderStatus.CANCELLED);
        when(svc.cancelOrder(1L, 42L, "USER", "Bearer token", EMAIL)).thenReturn(cancelled);

        mvc.perform(patch("/orders/1/cancel")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", "Bearer token")
                .requestAttr("userEmail", EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
