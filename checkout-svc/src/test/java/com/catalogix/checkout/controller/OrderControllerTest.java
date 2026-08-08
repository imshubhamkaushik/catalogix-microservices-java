package com.catalogix.checkout.controller;

import com.catalogix.checkout.dto.*;
import com.catalogix.checkout.exception.ForbiddenException;
import com.catalogix.checkout.exception.InvalidOrderStateException;
import com.catalogix.checkout.exception.ProductUnavailableException;
import com.catalogix.checkout.model.OrderStatus;
import com.catalogix.checkout.security.JwtAuthFilter;
import com.catalogix.checkout.security.RateLimiterFilter;
import com.catalogix.checkout.svc.CheckoutSvc;
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
//
// This only covers OrderController, the sole browser-facing controller in checkout-svc.
// AdminController (outbox visibility) has no dedicated slice test yet — see CheckoutSvcTest
// for outbox-write coverage via CheckoutSvc itself.
@WebMvcTest(
        controllers = OrderController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class OrderControllerTest {

    private static final String EMAIL = "buyer@example.com";
    private static final String TOKEN = "Bearer token";

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private CheckoutSvc svc;

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

    private OrderResponse sampleResponse(OrderStatus status) {
        OrderItemResponse item = new OrderItemResponse(
                1L, "Phone", 2, new BigDecimal("100.00"), new BigDecimal("200.00"));
        return new OrderResponse(1L, 42L, status, new BigDecimal("200.00"),
                Instant.now(), List.of(item));
    }

    // ---- POST /orders (direct API) ----

    @Test
    @SuppressWarnings("null")
    void createReturnsCreatedWhenNewOrder() throws Exception {
        when(svc.createOrder(eq(42L), any(CreateOrderRequest.class), eq(TOKEN), isNull()))
                .thenReturn(new CheckoutSvc.OrderCreationResult(sampleResponse(OrderStatus.PENDING_PAYMENT), true));

        mvc.perform(post("/orders")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    @SuppressWarnings("null")
    void createReturnsOkNotCreatedWhenIdempotencyKeyMatchesExistingOrder() throws Exception {
        when(svc.createOrder(eq(42L), any(CreateOrderRequest.class), eq(TOKEN), eq("key-abc")))
                .thenReturn(new CheckoutSvc.OrderCreationResult(sampleResponse(OrderStatus.PENDING_PAYMENT), false));

        mvc.perform(post("/orders")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", TOKEN)
                .header("Idempotency-Key", "key-abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    @SuppressWarnings("null")
    void createReturnsConflictWhenProductUnavailable() throws Exception {
        when(svc.createOrder(eq(42L), any(CreateOrderRequest.class), eq(TOKEN), isNull()))
                .thenThrow(new ProductUnavailableException("Insufficient stock for product 1"));

        mvc.perform(post("/orders")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    @SuppressWarnings("null")
    void createRecoversFromIdempotencyKeyRaceByReturningWinningOrder() throws Exception {
        when(svc.createOrder(eq(42L), any(CreateOrderRequest.class), eq(TOKEN), eq("key-abc")))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(svc.findExistingByIdempotencyKey(42L, "key-abc"))
                .thenReturn(Optional.of(sampleResponse(OrderStatus.PENDING_PAYMENT)));

        mvc.perform(post("/orders")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", TOKEN)
                .header("Idempotency-Key", "key-abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    // ---- POST /orders/checkout (cart-driven) ----
    // This is what the frontend's "Checkout" button actually calls — there is
    // no separate CartController in checkout-svc; cart-svc owns the cart, and
    // CheckoutSvc.checkoutFromCart pulls its contents via CartClient.

    @Test
    @SuppressWarnings("null")
    void checkoutCreatesOrderFromCart() throws Exception {
        when(svc.checkoutFromCart(eq(42L), eq(TOKEN), isNull()))
                .thenReturn(new CheckoutSvc.OrderCreationResult(sampleResponse(OrderStatus.PENDING_PAYMENT), true));

        mvc.perform(post("/orders/checkout")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    @SuppressWarnings("null")
    void checkoutRecoversFromIdempotencyKeyRaceByReturningWinningOrder() throws Exception {
        when(svc.checkoutFromCart(eq(42L), eq(TOKEN), eq("key-abc")))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(svc.findExistingByIdempotencyKey(42L, "key-abc"))
                .thenReturn(Optional.of(sampleResponse(OrderStatus.PENDING_PAYMENT)));

        mvc.perform(post("/orders/checkout")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", TOKEN)
                .header("Idempotency-Key", "key-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    // ---- GET /orders, GET /orders/{id} ----

    @Test
    void listReturnsOwnOrdersForRegularUser() throws Exception {
        PagedResponse<OrderResponse> page = new PagedResponse<>(List.of(sampleResponse(OrderStatus.CONFIRMED)), 0, 20, 1, 1);
        when(svc.listOrders(eq(42L), eq("USER"), any(Pageable.class))).thenReturn(page);

        mvc.perform(get("/orders").requestAttr("userId", 42L).requestAttr("userRole", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @SuppressWarnings("null")
    void getOneReturnsForbiddenWhenNotOwner() throws Exception {
        when(svc.getOrder(1L, 99L, "USER"))
                .thenThrow(new ForbiddenException("You may only view or manage your own orders"));

        mvc.perform(get("/orders/1").requestAttr("userId", 99L).requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    // ---- POST /orders/{id}/pay ----

    @Test
    @SuppressWarnings("null")
    void payReturnsOkWithConfirmedOrderOnSuccess() throws Exception {
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");
        req.setCardLast4("4242");

        OrderResponse confirmed = sampleResponse(OrderStatus.CONFIRMED);
        when(svc.payOrder(eq(1L), eq(42L), eq("USER"), any(PayOrderRequest.class), eq(TOKEN), eq(EMAIL)))
                .thenReturn(new CheckoutSvc.OrderPaymentResult(confirmed, true));

        mvc.perform(post("/orders/1/pay")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", TOKEN)
                .requestAttr("userEmail", EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"));
    }

    @Test
    @SuppressWarnings("null")
    void payReturnsOkWithFailedPaymentStatusOnDecline() throws Exception {
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");
        req.setCardLast4("0000");

        OrderResponse cancelled = sampleResponse(OrderStatus.CANCELLED);
        when(svc.payOrder(eq(1L), eq(42L), eq("USER"), any(PayOrderRequest.class), eq(TOKEN), eq(EMAIL)))
                .thenReturn(new CheckoutSvc.OrderPaymentResult(cancelled, false));

        // A decline is a legitimate business outcome, not an HTTP error — the
        // endpoint still returns 200 with payment.status = FAILED, matching
        // CheckoutSvc.payOrder/PaymentClient.process's translation of a 402
        // from payment-svc into a normal (not exceptional) return value.
        mvc.perform(post("/orders/1/pay")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", TOKEN)
                .requestAttr("userEmail", EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payment.status").value("FAILED"));
    }

    @Test
    @SuppressWarnings("null")
    void payReturnsConflictWhenOrderNotAwaitingPayment() throws Exception {
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");

        when(svc.payOrder(eq(1L), eq(42L), eq("USER"), any(PayOrderRequest.class), eq(TOKEN), eq(EMAIL)))
                .thenThrow(new InvalidOrderStateException("Order 1 is not awaiting payment"));

        mvc.perform(post("/orders/1/pay")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", TOKEN)
                .requestAttr("userEmail", EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    // ---- PATCH /orders/{id}/status ----

    @Test
    @SuppressWarnings("null")
    void updateStatusAllowsAdmin() throws Exception {
        UpdateOrderStatusRequest req = new UpdateOrderStatusRequest();
        req.setStatus(OrderStatus.SHIPPED);
        when(svc.updateStatus(1L, OrderStatus.SHIPPED)).thenReturn(sampleResponse(OrderStatus.SHIPPED));

        mvc.perform(patch("/orders/1/status")
                .requestAttr("userRole", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    @SuppressWarnings("null")
    void updateStatusRejectsNonAdmin() throws Exception {
        UpdateOrderStatusRequest req = new UpdateOrderStatusRequest();
        req.setStatus(OrderStatus.SHIPPED);

        mvc.perform(patch("/orders/1/status")
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // ---- PATCH /orders/{id}/cancel ----

    @Test
    @SuppressWarnings("null")
    void cancelReturnsOk() throws Exception {
        when(svc.cancelOrder(1L, 42L, "USER", TOKEN, EMAIL)).thenReturn(sampleResponse(OrderStatus.CANCELLED));

        mvc.perform(patch("/orders/1/cancel")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .requestAttr("bearerToken", TOKEN)
                .requestAttr("userEmail", EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
