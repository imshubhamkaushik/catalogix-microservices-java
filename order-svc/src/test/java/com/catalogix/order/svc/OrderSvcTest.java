package com.catalogix.order.svc;

import com.catalogix.order.client.ProductSvcClient;
import com.catalogix.order.dto.*;
import com.catalogix.order.event.OrderCancelledEvent;
import com.catalogix.order.event.OrderConfirmedEvent;
import com.catalogix.order.exception.CouponInvalidException;
import com.catalogix.order.exception.InvalidOrderStateException;
import com.catalogix.order.exception.ProductUnavailableException;
import com.catalogix.order.model.*;
import com.catalogix.order.repository.OrderRepository;
import com.catalogix.order.repository.StockAdjustmentOutboxRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("null")
class OrderSvcTest {

    @Mock private OrderRepository repo;
    @Mock private StockAdjustmentOutboxRepository outboxRepo;
    @Mock private ProductSvcClient productSvcClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CouponSvc couponSvc;
    @Mock private PaymentSvc paymentSvc;

    @InjectMocks
    private OrderSvc svc;

    private static final String TOKEN = "Bearer test-token";
    private static final String EMAIL = "buyer@example.com";
    private static final String COUPON_SAVE10 = "SAVE10";
    private static final String MOCK_CARD = "MOCK_CARD";
    private static final String MOCK_REF = "MOCK-REF";
    private static final String ROLE_USER = "USER";
    private static final String PHONE = "Phone";
    private static final String PRICE_100 = "100.00";
    private static final String PRICE_200 = "200.00";
    private static final Long USER_ID = 42L;
    private static final Long PRODUCT_ID = 1L;
    private static final Long ORDER_ID = 5L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(repo.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) o.setId(PRODUCT_ID);
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
        when(productSvcClient.fetchProduct(PRODUCT_ID, TOKEN)).thenReturn(product(PRODUCT_ID, PHONE, PRICE_100, 10));

        OrderSvc.OrderCreationResult result = svc.createOrder(USER_ID, requestFor(PRODUCT_ID, 2), TOKEN, null, EMAIL);

        assertTrue(result.wasNew());
        assertEquals(OrderStatus.PENDING_PAYMENT, result.order().getStatus());
        assertEquals(new BigDecimal(PRICE_200), result.order().getTotalAmount());
        verify(productSvcClient).adjustStock(PRODUCT_ID, -2, TOKEN);
        // Confirmation event fires on successful *payment*, not creation — see payOrder tests.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void createOrderAppliesValidCouponDiscount() {
        when(productSvcClient.fetchProduct(PRODUCT_ID, TOKEN)).thenReturn(product(PRODUCT_ID, PHONE, PRICE_100, 10));
        Coupon coupon = percentageCoupon(COUPON_SAVE10, 10);
        when(couponSvc.validate(COUPON_SAVE10)).thenReturn(coupon);
        when(couponSvc.calculateDiscount(coupon, new BigDecimal(PRICE_200))).thenReturn(new BigDecimal("20.00"));

        CreateOrderRequest req = requestFor(PRODUCT_ID, 2);
        req.setCouponCode(COUPON_SAVE10);

        OrderSvc.OrderCreationResult result = svc.createOrder(USER_ID, req, TOKEN, null, EMAIL);

        assertEquals(COUPON_SAVE10, result.order().getAppliedCouponCode());
        assertEquals(new BigDecimal("20.00"), result.order().getDiscountAmount());
        assertEquals(new BigDecimal("180.00"), result.order().getTotalAmount());
        verify(couponSvc).recordUsage(coupon);
    }

    @Test
    void createOrderCompensatesReservedStockWhenCouponIsInvalid() {
        when(productSvcClient.fetchProduct(PRODUCT_ID, TOKEN)).thenReturn(product(PRODUCT_ID, PHONE, PRICE_100, 10));
        when(couponSvc.validate("BADCODE")).thenThrow(new CouponInvalidException("Coupon code not found: BADCODE"));

        CreateOrderRequest req = requestFor(PRODUCT_ID, 2);
        req.setCouponCode("BADCODE");

        assertThrows(CouponInvalidException.class, () -> svc.createOrder(USER_ID, req, TOKEN, null, EMAIL));

        // The stock reserved before the coupon check failed must be released.
        verify(productSvcClient).adjustStock(PRODUCT_ID, -2, TOKEN);
        verify(productSvcClient).adjustStock(PRODUCT_ID, 2, TOKEN);
        verify(repo, never()).save(any());
        verify(couponSvc, never()).recordUsage(any());
    }

    @Test
    void createOrderThrowsAndDoesNotSaveWhenStockInsufficient() {
        doThrow(new ProductUnavailableException("Insufficient stock for product 1"))
                .when(productSvcClient).adjustStock(PRODUCT_ID, -5, TOKEN);
        when(productSvcClient.fetchProduct(PRODUCT_ID, TOKEN)).thenReturn(product(PRODUCT_ID, PHONE, PRICE_100, 1));

        CreateOrderRequest req = requestFor(PRODUCT_ID, 5);
        assertThrows(ProductUnavailableException.class,
                () -> svc.createOrder(USER_ID, req, TOKEN, null, EMAIL));
        verify(repo, never()).save(any());
    }

    @Test
    void createOrderWithMatchingIdempotencyKeyReturnsExistingOrderWithoutReReserving() {
        Order existing = new Order();
        existing.setId(9L); existing.setUserId(USER_ID); existing.setStatus(OrderStatus.CONFIRMED);
        existing.setTotalAmount(new BigDecimal("50.00"));
        when(repo.findByUserIdAndIdempotencyKey(USER_ID, "key-123")).thenReturn(Optional.of(existing));

        OrderSvc.OrderCreationResult result = svc.createOrder(USER_ID, requestFor(PRODUCT_ID, 2), TOKEN, "key-123", EMAIL);

        assertFalse(result.wasNew());
        assertEquals(9L, result.order().getId());
        verifyNoInteractions(productSvcClient);
        verify(repo, never()).save(any());
    }

    // ---- payOrder ----

    private Order pendingPaymentOrder() {
        Order order = new Order();
        order.setId(ORDER_ID); order.setUserId(USER_ID); order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalAmount(new BigDecimal(PRICE_200));
        order.addItem(new OrderItem(PRODUCT_ID, PHONE, 2, new BigDecimal(PRICE_100)));
        return order;
    }

    @Test
    void payOrderConfirmsAndNotifiesOnSuccessfulPayment() {
        Order order = pendingPaymentOrder();
        when(repo.findById(ORDER_ID)).thenReturn(Optional.of(order));
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod(MOCK_CARD);
        req.setCardLast4("4242");
        when(paymentSvc.process(ORDER_ID, new BigDecimal(PRICE_200), req))
                .thenReturn(new PaymentResponse(PRODUCT_ID, ORDER_ID, new BigDecimal(PRICE_200), MOCK_CARD,
                        PaymentStatus.SUCCEEDED, MOCK_REF, null));

        OrderSvc.OrderPaymentResult result = svc.payOrder(ORDER_ID, USER_ID, ROLE_USER, req, TOKEN, EMAIL);

        assertEquals(OrderStatus.CONFIRMED, result.order().getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, result.payment().getStatus());
        verify(eventPublisher).publishEvent(any(OrderConfirmedEvent.class));
        verify(productSvcClient, never()).adjustStock(anyLong(), anyInt(), anyString());
    }

    @Test
    void payOrderCancelsAndReleasesStockOnDecline() {
        Order order = pendingPaymentOrder();
        when(repo.findById(ORDER_ID)).thenReturn(Optional.of(order));
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod(MOCK_CARD);
        req.setCardLast4("0000"); // magic decline value
        when(paymentSvc.process(eq(ORDER_ID), any(), eq(req)))
                .thenReturn(new PaymentResponse(PRODUCT_ID, ORDER_ID, new BigDecimal(PRICE_200), MOCK_CARD,
                        PaymentStatus.FAILED, MOCK_REF, null));

        OrderSvc.OrderPaymentResult result = svc.payOrder(ORDER_ID, USER_ID, ROLE_USER, req, TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, result.order().getStatus());
        assertEquals(PaymentStatus.FAILED, result.payment().getStatus());
        verify(productSvcClient).adjustStock(PRODUCT_ID, 2, TOKEN); // stock released
        verify(eventPublisher, never()).publishEvent(any(OrderConfirmedEvent.class));
    }

    @Test
    void payOrderReleasesCouponUsageOnDecline() {
        Order order = pendingPaymentOrder();
        order.setAppliedCouponCode(COUPON_SAVE10);
        when(repo.findById(ORDER_ID)).thenReturn(Optional.of(order));
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod(MOCK_CARD);
        req.setCardLast4("0000");
        when(paymentSvc.process(eq(ORDER_ID), any(), eq(req)))
                .thenReturn(new PaymentResponse(PRODUCT_ID, ORDER_ID, new BigDecimal(PRICE_200), MOCK_CARD,
                        PaymentStatus.FAILED, MOCK_REF, null));

        svc.payOrder(ORDER_ID, USER_ID, ROLE_USER, req, TOKEN, EMAIL);

        verify(couponSvc).releaseUsage(COUPON_SAVE10);
    }

    @Test
    void payOrderRejectsWhenOrderNotAwaitingPayment() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(ORDER_ID)).thenReturn(Optional.of(order));
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod(MOCK_CARD);

        assertThrows(InvalidOrderStateException.class, () -> svc.payOrder(ORDER_ID, USER_ID, ROLE_USER, req, TOKEN, EMAIL));
        verifyNoInteractions(paymentSvc);
    }

    // ---- updateStatus ----

    @Test
    void updateStatusAllowsConfirmedToShipped() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(ORDER_ID)).thenReturn(Optional.of(order));

        OrderResponse resp = svc.updateStatus(ORDER_ID, OrderStatus.SHIPPED);
        assertEquals(OrderStatus.SHIPPED, resp.getStatus());
    }

    @Test
    void updateStatusRejectsSkippingAStage() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThrows(InvalidOrderStateException.class, () -> svc.updateStatus(ORDER_ID, OrderStatus.DELIVERED));
    }

    // ---- cancelOrder ----

    @Test
    void cancelOrderRestocksReleasesCouponAndNotifies() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        order.setAppliedCouponCode(COUPON_SAVE10);
        when(repo.findById(ORDER_ID)).thenReturn(Optional.of(order));

        var resp = svc.cancelOrder(ORDER_ID, USER_ID, ROLE_USER, TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus());
        verify(productSvcClient).adjustStock(PRODUCT_ID, 2, TOKEN);
        verify(couponSvc).releaseUsage(COUPON_SAVE10);
        verify(eventPublisher).publishEvent(any(OrderCancelledEvent.class));
    }

    @Test
    void cancelOrderRejectsShippedOrders() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.SHIPPED);
        when(repo.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThrows(InvalidOrderStateException.class, () -> svc.cancelOrder(ORDER_ID, USER_ID, ROLE_USER, TOKEN, EMAIL));
        verifyNoInteractions(productSvcClient);
    }

    @Test
    void cancelOrderIsANoOpIfAlreadyCancelled() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CANCELLED);
        when(repo.findById(ORDER_ID)).thenReturn(Optional.of(order));

        var resp = svc.cancelOrder(ORDER_ID, USER_ID, ROLE_USER, TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus());
        verifyNoInteractions(productSvcClient);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelOrderQueuesToOutboxWhenRestockFailsLive() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(ORDER_ID)).thenReturn(Optional.of(order));
        doThrow(new ProductUnavailableException("unreachable"))
                .when(productSvcClient).adjustStock(PRODUCT_ID, 2, TOKEN);

        var resp = svc.cancelOrder(ORDER_ID, USER_ID, ROLE_USER, TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus());
        verify(outboxRepo).save(argThat((StockAdjustmentOutbox entry) ->
                entry.getProductId().equals(PRODUCT_ID) && entry.getDelta() == 2));
    }
}