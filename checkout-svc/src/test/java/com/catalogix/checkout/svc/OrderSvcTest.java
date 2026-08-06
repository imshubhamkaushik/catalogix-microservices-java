package com.catalogix.checkout.svc;

import com.catalogix.checkout.client.ProductSvcClient;
import com.catalogix.checkout.dto.*;
import com.catalogix.checkout.event.OrderCancelledEvent;
import com.catalogix.checkout.event.OrderConfirmedEvent;
import com.catalogix.checkout.exception.CouponInvalidException;
import com.catalogix.checkout.exception.InvalidOrderStateException;
import com.catalogix.checkout.exception.ProductUnavailableException;
import com.catalogix.checkout.model.*;
import com.catalogix.checkout.repository.OrderRepository;
import com.catalogix.checkout.repository.StockAdjustmentOutboxRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderSvcTest {

    @Mock private OrderRepository repo;
    @Mock private StockAdjustmentOutboxRepository outboxRepo;
    @Mock private ProductSvcClient productSvcClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CouponSvc couponSvc;
    @Mock private PaymentSvc paymentSvc;

    private OrderSvc svc;

    private static final String TOKEN = "Bearer test-token";
    private static final String EMAIL = "buyer@example.com";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new OrderSvc(repo, outboxRepo, productSvcClient, eventPublisher, couponSvc, paymentSvc);
        when(repo.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) o.setId(1L);
            return o;
        });
    }

    private ProductLookupResponse product(Long id, String name, String price, int stock) {
        ProductLookupResponse p = new ProductLookupResponse();
        p.setId(id); p.setName(name); p.setPrice(new BigDecimal(price)); p.setStockQuantity(stock);
        return p;
    }

    private CreateOrderRequest requestFor(Long productId, int qty) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(qty);
        CreateOrderRequest req = new CreateOrderRequest();
        req.setItems(List.of(item));
        return req;
    }

    private Coupon percentageCoupon(String code, int percent) {
        Coupon c = new Coupon();
        c.setCode(code);
        c.setDiscountType(DiscountType.PERCENTAGE);
        c.setDiscountValue(BigDecimal.valueOf(percent));
        return c;
    }

    // ---- createOrder ----

    @Test
    void createOrderSucceedsEntersPendingPaymentAndSendsNoEmailYet() {
        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 10));

        OrderSvc.OrderCreationResult result = svc.createOrder(42L, requestFor(1L, 2), TOKEN, null, EMAIL);

        assertTrue(result.wasNew());
        assertEquals(OrderStatus.PENDING_PAYMENT, result.order().getStatus());
        assertEquals(new BigDecimal("200.00"), result.order().getTotalAmount());
        verify(productSvcClient).adjustStock(1L, -2, TOKEN);
        // Confirmation event fires on successful *payment*, not creation — see payOrder tests.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void createOrderAppliesValidCouponDiscount() {
        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 10));
        Coupon coupon = percentageCoupon("SAVE10", 10);
        when(couponSvc.validate("SAVE10")).thenReturn(coupon);
        when(couponSvc.calculateDiscount(coupon, new BigDecimal("200.00"))).thenReturn(new BigDecimal("20.00"));

        CreateOrderRequest req = requestFor(1L, 2);
        req.setCouponCode("SAVE10");

        OrderSvc.OrderCreationResult result = svc.createOrder(42L, req, TOKEN, null, EMAIL);

        assertEquals("SAVE10", result.order().getAppliedCouponCode());
        assertEquals(new BigDecimal("20.00"), result.order().getDiscountAmount());
        assertEquals(new BigDecimal("180.00"), result.order().getTotalAmount());
        verify(couponSvc).recordUsage(coupon);
    }

    @Test
    void createOrderCompensatesReservedStockWhenCouponIsInvalid() {
        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 10));
        when(couponSvc.validate("BADCODE")).thenThrow(new CouponInvalidException("Coupon code not found: BADCODE"));

        CreateOrderRequest req = requestFor(1L, 2);
        req.setCouponCode("BADCODE");

        assertThrows(CouponInvalidException.class, () -> svc.createOrder(42L, req, TOKEN, null, EMAIL));

        // The stock reserved before the coupon check failed must be released.
        verify(productSvcClient).adjustStock(1L, -2, TOKEN);
        verify(productSvcClient).adjustStock(1L, 2, TOKEN);
        verify(repo, never()).save(any());
        verify(couponSvc, never()).recordUsage(any());
    }

    @Test
    void createOrderThrowsAndDoesNotSaveWhenStockInsufficient() {
        doThrow(new ProductUnavailableException("Insufficient stock for product 1"))
                .when(productSvcClient).adjustStock(eq(1L), eq(-5), eq(TOKEN));
        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 1));

        assertThrows(ProductUnavailableException.class,
                () -> svc.createOrder(42L, requestFor(1L, 5), TOKEN, null, EMAIL));
        verify(repo, never()).save(any());
    }

    @Test
    void createOrderWithMatchingIdempotencyKeyReturnsExistingOrderWithoutReReserving() {
        Order existing = new Order();
        existing.setId(9L); existing.setUserId(42L); existing.setStatus(OrderStatus.CONFIRMED);
        existing.setTotalAmount(new BigDecimal("50.00"));
        when(repo.findByUserIdAndIdempotencyKey(42L, "key-123")).thenReturn(Optional.of(existing));

        OrderSvc.OrderCreationResult result = svc.createOrder(42L, requestFor(1L, 2), TOKEN, "key-123", EMAIL);

        assertFalse(result.wasNew());
        assertEquals(9L, result.order().getId());
        verifyNoInteractions(productSvcClient);
        verify(repo, never()).save(any());
    }

    // ---- payOrder ----

    private Order pendingPaymentOrder() {
        Order order = new Order();
        order.setId(5L); order.setUserId(42L); order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalAmount(new BigDecimal("200.00"));
        order.addItem(new OrderItem(1L, "Phone", 2, new BigDecimal("100.00")));
        return order;
    }

    @Test
    void payOrderConfirmsAndNotifiesOnSuccessfulPayment() {
        Order order = pendingPaymentOrder();
        when(repo.findById(5L)).thenReturn(Optional.of(order));
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");
        req.setCardLast4("4242");
        when(paymentSvc.process(eq(5L), eq(new BigDecimal("200.00")), eq(req)))
                .thenReturn(new PaymentResponse(1L, 5L, new BigDecimal("200.00"), "MOCK_CARD",
                        PaymentStatus.SUCCEEDED, "MOCK-REF", null));

        OrderSvc.OrderPaymentResult result = svc.payOrder(5L, 42L, "USER", req, TOKEN, EMAIL);

        assertEquals(OrderStatus.CONFIRMED, result.order().getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, result.payment().getStatus());
        verify(eventPublisher).publishEvent(any(OrderConfirmedEvent.class));
        verify(productSvcClient, never()).adjustStock(anyLong(), anyInt(), anyString());
    }

    @Test
    void payOrderCancelsAndReleasesStockOnDecline() {
        Order order = pendingPaymentOrder();
        when(repo.findById(5L)).thenReturn(Optional.of(order));
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");
        req.setCardLast4("0000"); // magic decline value
        when(paymentSvc.process(eq(5L), any(), eq(req)))
                .thenReturn(new PaymentResponse(1L, 5L, new BigDecimal("200.00"), "MOCK_CARD",
                        PaymentStatus.FAILED, "MOCK-REF", null));

        OrderSvc.OrderPaymentResult result = svc.payOrder(5L, 42L, "USER", req, TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, result.order().getStatus());
        assertEquals(PaymentStatus.FAILED, result.payment().getStatus());
        verify(productSvcClient).adjustStock(1L, 2, TOKEN); // stock released
        verify(eventPublisher, never()).publishEvent(any(OrderConfirmedEvent.class));
    }

    @Test
    void payOrderReleasesCouponUsageOnDecline() {
        Order order = pendingPaymentOrder();
        order.setAppliedCouponCode("SAVE10");
        when(repo.findById(5L)).thenReturn(Optional.of(order));
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");
        req.setCardLast4("0000");
        when(paymentSvc.process(eq(5L), any(), eq(req)))
                .thenReturn(new PaymentResponse(1L, 5L, new BigDecimal("200.00"), "MOCK_CARD",
                        PaymentStatus.FAILED, "MOCK-REF", null));

        svc.payOrder(5L, 42L, "USER", req, TOKEN, EMAIL);

        verify(couponSvc).releaseUsage("SAVE10");
    }

    @Test
    void payOrderRejectsWhenOrderNotAwaitingPayment() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");

        assertThrows(InvalidOrderStateException.class, () -> svc.payOrder(5L, 42L, "USER", req, TOKEN, EMAIL));
        verifyNoInteractions(paymentSvc);
    }

    // ---- updateStatus ----

    @Test
    void updateStatusAllowsConfirmedToShipped() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));

        OrderResponse resp = svc.updateStatus(5L, OrderStatus.SHIPPED);
        assertEquals(OrderStatus.SHIPPED, resp.getStatus());
    }

    @Test
    void updateStatusRejectsSkippingAStage() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));

        assertThrows(InvalidOrderStateException.class, () -> svc.updateStatus(5L, OrderStatus.DELIVERED));
    }

    // ---- cancelOrder ----

    @Test
    void cancelOrderRestocksReleasesCouponAndNotifies() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        order.setAppliedCouponCode("SAVE10");
        when(repo.findById(5L)).thenReturn(Optional.of(order));

        var resp = svc.cancelOrder(5L, 42L, "USER", TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus());
        verify(productSvcClient).adjustStock(1L, 2, TOKEN);
        verify(couponSvc).releaseUsage("SAVE10");
        verify(eventPublisher).publishEvent(any(OrderCancelledEvent.class));
    }

    @Test
    void cancelOrderRejectsShippedOrders() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.SHIPPED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));

        assertThrows(InvalidOrderStateException.class, () -> svc.cancelOrder(5L, 42L, "USER", TOKEN, EMAIL));
        verifyNoInteractions(productSvcClient);
    }

    @Test
    void cancelOrderIsANoOpIfAlreadyCancelled() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CANCELLED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));

        var resp = svc.cancelOrder(5L, 42L, "USER", TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus());
        verifyNoInteractions(productSvcClient);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelOrderQueuesToOutboxWhenRestockFailsLive() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));
        doThrow(new ProductUnavailableException("unreachable"))
                .when(productSvcClient).adjustStock(eq(1L), eq(2), eq(TOKEN));

        var resp = svc.cancelOrder(5L, 42L, "USER", TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus());
        verify(outboxRepo).save(argThat((StockAdjustmentOutbox entry) ->
                entry.getProductId().equals(1L) && entry.getDelta() == 2));
    }
}
