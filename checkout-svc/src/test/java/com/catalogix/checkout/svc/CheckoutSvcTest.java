package com.catalogix.checkout.svc;

import com.catalogix.checkout.client.CartClient;
import com.catalogix.checkout.client.CatalogClient;
import com.catalogix.checkout.client.InventoryClient;
import com.catalogix.checkout.client.PaymentClient;
import com.catalogix.checkout.client.PromotionsClient;
import com.catalogix.checkout.dto.CreateOrderRequest;
import com.catalogix.checkout.dto.OrderItemRequest;
import com.catalogix.checkout.dto.PayOrderRequest;
import com.catalogix.checkout.event.OrderCancelledEvent;
import com.catalogix.checkout.event.OrderConfirmedEvent;
import com.catalogix.checkout.exception.CouponInvalidException;
import com.catalogix.checkout.exception.InvalidOrderStateException;
import com.catalogix.checkout.exception.ProductUnavailableException;
import com.catalogix.checkout.model.CompensationOutbox;
import com.catalogix.checkout.model.Order;
import com.catalogix.checkout.model.OrderItem;
import com.catalogix.checkout.model.OrderStatus;
import com.catalogix.checkout.repository.CompensationOutboxRepository;
import com.catalogix.checkout.repository.OrderRepository;

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

// Tests CheckoutSvc directly against its real collaborators (CatalogClient,
// InventoryClient, PromotionsClient, PaymentClient, CartClient) — the HTTP
// clients this service actually has, replacing an older test suite written
// against a pre-split, single-service design (a local ProductSvcClient plus
// in-process CartSvc/CouponSvc/PaymentSvc) that no longer exists here; that
// functionality now lives in cart-svc/promotions-svc/payment-svc, each with
// their own test coverage (payment-svc's PaymentSvcTest in particular is the
// direct analog of what used to be tested from in here).
class CheckoutSvcTest {

    @Mock private OrderRepository repo;
    @Mock private CompensationOutboxRepository outboxRepo;
    @Mock private CatalogClient catalogClient;
    @Mock private InventoryClient inventoryClient;
    @Mock private PromotionsClient promotionsClient;
    @Mock private PaymentClient paymentClient;
    @Mock private CartClient cartClient;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CheckoutSvc svc;

    private static final String TOKEN = "Bearer test-token";
    private static final String EMAIL = "buyer@example.com";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new CheckoutSvc(repo, outboxRepo, catalogClient, inventoryClient,
                promotionsClient, paymentClient, cartClient, eventPublisher);
        when(repo.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) o.setId(1L);
            return o;
        });
    }

    private CatalogClient.ProductDto product(Long id, String name, String price) {
        return new CatalogClient.ProductDto(id, name, new BigDecimal(price));
    }

    private CreateOrderRequest requestFor(Long productId, int qty) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(qty);
        CreateOrderRequest req = new CreateOrderRequest();
        req.setItems(List.of(item));
        return req;
    }

    // ---- createOrder (direct API) ----

    @Test
    void createOrderSucceedsEntersPendingPaymentAndSendsNoEmailYet() {
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));

        CheckoutSvc.OrderCreationResult result = svc.createOrder(42L, requestFor(1L, 2), TOKEN, null);

        assertTrue(result.wasNew());
        assertEquals(OrderStatus.PENDING_PAYMENT, result.order().getStatus());
        assertEquals(new BigDecimal("200.00"), result.order().getTotalAmount());
        verify(inventoryClient).adjust(1L, -2, TOKEN);
        // Confirmation event fires on successful *payment*, not creation — see payOrder tests.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void createOrderAppliesValidCouponDiscount() {
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(promotionsClient.commit(eq("SAVE10"), eq(new BigDecimal("200.00")), eq(TOKEN)))
                .thenReturn(new PromotionsClient.DiscountDto("SAVE10", new BigDecimal("20.00")));

        CreateOrderRequest req = requestFor(1L, 2);
        req.setCouponCode("SAVE10");

        CheckoutSvc.OrderCreationResult result = svc.createOrder(42L, req, TOKEN, null);

        assertEquals("SAVE10", result.order().getAppliedCouponCode());
        assertEquals(new BigDecimal("20.00"), result.order().getDiscountAmount());
        assertEquals(new BigDecimal("180.00"), result.order().getTotalAmount());
    }

    @Test
    void createOrderCompensatesReservedStockWhenCouponIsInvalid() {
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(promotionsClient.commit(eq("BADCODE"), any(), eq(TOKEN)))
                .thenThrow(new CouponInvalidException("Coupon is not valid: BADCODE"));

        CreateOrderRequest req = requestFor(1L, 2);
        req.setCouponCode("BADCODE");

        assertThrows(CouponInvalidException.class, () -> svc.createOrder(42L, req, TOKEN, null));

        // The stock reserved before the coupon check failed must be released.
        verify(inventoryClient).adjust(1L, -2, TOKEN);
        verify(inventoryClient).adjust(1L, 2, TOKEN);
        verify(repo, never()).save(any());
    }

    @Test
    void createOrderThrowsAndDoesNotSaveWhenStockInsufficient() {
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        doThrow(new ProductUnavailableException("Insufficient stock for product 1"))
                .when(inventoryClient).adjust(eq(1L), eq(-5), eq(TOKEN));

        assertThrows(ProductUnavailableException.class,
                () -> svc.createOrder(42L, requestFor(1L, 5), TOKEN, null));
        verify(repo, never()).save(any());
    }

    @Test
    void createOrderWithMatchingIdempotencyKeyReturnsExistingOrderWithoutReReserving() {
        Order existing = new Order();
        existing.setId(9L); existing.setUserId(42L); existing.setStatus(OrderStatus.CONFIRMED);
        existing.setTotalAmount(new BigDecimal("50.00"));
        when(repo.findByUserIdAndIdempotencyKey(42L, "key-123")).thenReturn(Optional.of(existing));

        CheckoutSvc.OrderCreationResult result = svc.createOrder(42L, requestFor(1L, 2), TOKEN, "key-123");

        assertFalse(result.wasNew());
        assertEquals(9L, result.order().getId());
        verifyNoInteractions(catalogClient);
        verifyNoInteractions(inventoryClient);
        verify(repo, never()).save(any());
    }

    // ---- checkoutFromCart ----

    @Test
    void checkoutFromCartPlacesOrderFromHandoffAndClearsCartWhenNew() {
        CartClient.Handoff handoff = new CartClient.Handoff(
                List.of(new CartClient.ItemLine(1L, 2)), null);
        when(cartClient.handoff(TOKEN)).thenReturn(handoff);
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));

        CheckoutSvc.OrderCreationResult result = svc.checkoutFromCart(42L, TOKEN, null);

        assertTrue(result.wasNew());
        assertEquals(new BigDecimal("200.00"), result.order().getTotalAmount());
        verify(cartClient).clear(TOKEN);
    }

    @Test
    void checkoutFromCartDoesNotClearCartWhenIdempotencyKeyReturnsExistingOrder() {
        Order existing = new Order();
        existing.setId(9L); existing.setUserId(42L); existing.setStatus(OrderStatus.CONFIRMED);
        existing.setTotalAmount(new BigDecimal("50.00"));
        when(repo.findByUserIdAndIdempotencyKey(42L, "key-123")).thenReturn(Optional.of(existing));
        // checkoutFromCart always pulls the cart handoff up front, before the
        // idempotency check (which happens inside placeOrder) has a chance to
        // short-circuit — so the handoff still needs stubbing even though its
        // contents end up unused once the existing order is found.
        when(cartClient.handoff(TOKEN)).thenReturn(new CartClient.Handoff(List.of(), null));

        CheckoutSvc.OrderCreationResult result = svc.checkoutFromCart(42L, TOKEN, "key-123");

        assertFalse(result.wasNew());
        verify(cartClient, never()).clear(TOKEN);
    }

    @Test
    void checkoutFromCartStillReturnsTheOrderWhenClearingTheCartFails() {
        CartClient.Handoff handoff = new CartClient.Handoff(
                List.of(new CartClient.ItemLine(1L, 2)), null);
        when(cartClient.handoff(TOKEN)).thenReturn(handoff);
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        doThrow(new RuntimeException("cart-svc unreachable")).when(cartClient).clear(TOKEN);

        // Clearing the cart is best-effort: the order is already committed by
        // this point, so a failure here must not surface as an error to the
        // caller — see CheckoutSvc.checkoutFromCart's Javadoc.
        CheckoutSvc.OrderCreationResult result = svc.checkoutFromCart(42L, TOKEN, null);

        assertTrue(result.wasNew());
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
        when(paymentClient.process(eq(5L), eq(new BigDecimal("200.00")), eq(req), eq(TOKEN)))
                .thenReturn(new PaymentClient.PaymentOutcome(true, "MOCK-REF"));

        CheckoutSvc.OrderPaymentResult result = svc.payOrder(5L, 42L, "USER", req, TOKEN, EMAIL);

        assertEquals(OrderStatus.CONFIRMED, result.order().getStatus());
        assertTrue(result.paymentSucceeded());
        verify(eventPublisher).publishEvent(any(OrderConfirmedEvent.class));
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void payOrderCancelsAndReleasesStockOnDecline() {
        Order order = pendingPaymentOrder();
        when(repo.findById(5L)).thenReturn(Optional.of(order));
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");
        req.setCardLast4("0000"); // magic decline value
        when(paymentClient.process(eq(5L), any(), eq(req), eq(TOKEN)))
                .thenReturn(new PaymentClient.PaymentOutcome(false, null));

        CheckoutSvc.OrderPaymentResult result = svc.payOrder(5L, 42L, "USER", req, TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, result.order().getStatus());
        assertFalse(result.paymentSucceeded());
        verify(inventoryClient).adjust(1L, 2, TOKEN); // stock released
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
        when(paymentClient.process(eq(5L), any(), eq(req), eq(TOKEN)))
                .thenReturn(new PaymentClient.PaymentOutcome(false, null));

        svc.payOrder(5L, 42L, "USER", req, TOKEN, EMAIL);

        verify(promotionsClient).release("SAVE10", TOKEN);
    }

    @Test
    void payOrderRejectsWhenOrderNotAwaitingPayment() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");

        assertThrows(InvalidOrderStateException.class, () -> svc.payOrder(5L, 42L, "USER", req, TOKEN, EMAIL));
        verifyNoInteractions(paymentClient);
    }

    // ---- updateStatus ----

    @Test
    void updateStatusAllowsConfirmedToShipped() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));

        var resp = svc.updateStatus(5L, OrderStatus.SHIPPED);
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
        verify(inventoryClient).adjust(1L, 2, TOKEN);
        verify(promotionsClient).release("SAVE10", TOKEN);
        verify(eventPublisher).publishEvent(any(OrderCancelledEvent.class));
    }

    @Test
    void cancelOrderRejectsShippedOrders() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.SHIPPED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));

        assertThrows(InvalidOrderStateException.class, () -> svc.cancelOrder(5L, 42L, "USER", TOKEN, EMAIL));
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void cancelOrderIsANoOpIfAlreadyCancelled() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CANCELLED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));

        var resp = svc.cancelOrder(5L, 42L, "USER", TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus());
        verifyNoInteractions(inventoryClient);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelOrderQueuesToOutboxWhenRestockFailsLive() {
        Order order = pendingPaymentOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));
        doThrow(new ProductUnavailableException("unreachable"))
                .when(inventoryClient).adjust(eq(1L), eq(2), eq(TOKEN));

        var resp = svc.cancelOrder(5L, 42L, "USER", TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus());
        verify(outboxRepo).save(argThat((CompensationOutbox entry) ->
                entry.getProductId().equals(1L) && entry.getDelta() == 2));
    }
}
