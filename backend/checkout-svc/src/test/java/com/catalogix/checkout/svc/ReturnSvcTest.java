package com.catalogix.checkout.svc;

import com.catalogix.checkout.client.InventoryClient;
import com.catalogix.checkout.client.RefundClient;
import com.catalogix.checkout.dto.*;
import com.catalogix.checkout.exception.ForbiddenException;
import com.catalogix.checkout.exception.InvalidReturnException;
import com.catalogix.checkout.exception.OrderNotFoundException;
import com.catalogix.checkout.exception.RefundFailedException;
import com.catalogix.checkout.exception.ReturnRequestNotFoundException;
import com.catalogix.checkout.model.*;
import com.catalogix.checkout.repository.OrderRepository;
import com.catalogix.checkout.repository.ReturnRequestRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReturnSvcTest {

    @Mock private OrderRepository orderRepo;
    @Mock private ReturnRequestRepository returnRepo;
    @Mock private InventoryClient inventoryClient;
    @Mock private RefundClient refundClient;

    private ReturnSvc svc;

    private static final Long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new ReturnSvc(orderRepo, returnRepo, inventoryClient, refundClient);
    }

    private Order deliveredOrder(Instant deliveredAt) {
        Order order = new Order();
        order.setId(5L);
        order.setUserId(USER_ID);
        order.setStatus(OrderStatus.DELIVERED);
        order.setPaymentMethod(PaymentMethod.CARD);
        order.setPaymentReference("MOCK-CARD-abc");
        order.addItem(new OrderItem(1L, "Phone", 2, new BigDecimal("100.00")));
        order.addStatusEvent(OrderStatus.PENDING_PAYMENT, "Order placed");
        order.addStatusEvent(OrderStatus.CONFIRMED, "Payment confirmed");
        order.addStatusEvent(OrderStatus.SHIPPED, "Order shipped");
        // addStatusEvent always stamps "now" — overwrite the DELIVERED
        // event's timestamp directly so tests can control the return window.
        order.addStatusEvent(OrderStatus.DELIVERED, "Order delivered");
        order.getStatusEvents().get(order.getStatusEvents().size() - 1).setCreatedAt(deliveredAt);
        return order;
    }

    private RequestReturnRequest requestFor(long productId, int quantity) {
        RequestReturnRequest req = new RequestReturnRequest();
        req.setReason("Wrong size");
        ReturnItemRequest item = new ReturnItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        req.setItems(List.of(item));
        return req;
    }

    // ---- requestReturn: ownership & order state ----

    @Test
    void requestReturnThrowsWhenOrderDoesNotExist() {
        when(orderRepo.findById(5L)).thenReturn(Optional.empty());

        RequestReturnRequest request = requestFor(1L, 1);

            assertThrows(
                OrderNotFoundException.class,
                () -> svc.requestReturn(5L, USER_ID, "USER", request)
        );
    }

    @Test
    void requestReturnRejectsNonOwnerNonAdmin() {
        Order order = deliveredOrder(Instant.now());
        when(orderRepo.findById(5L)).thenReturn(Optional.of(order));

        RequestReturnRequest request = requestFor(1L, 1);

        assertThrows(
                ForbiddenException.class,
                () -> svc.requestReturn(5L, 999L, "USER", request)
        );
    }

    @Test
    void requestReturnAllowsAdminOnBehalfOfAnotherUser() {
        Order order = deliveredOrder(Instant.now());
        when(orderRepo.findById(5L)).thenReturn(Optional.of(order));
        when(returnRepo.findByOrderIdAndStatusIn(eq(5L), any())).thenReturn(List.of());
        when(returnRepo.save(any(ReturnRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        RequestReturnRequest request = requestFor(1L, 1);
        assertDoesNotThrow(() -> svc.requestReturn(5L, 999L, "ADMIN", request));
    }

    @Test
    void requestReturnRejectsAnOrderThatWasNeverDelivered() {
        Order order = new Order();
        order.setId(5L);
        order.setUserId(USER_ID);
        order.setStatus(OrderStatus.SHIPPED);
        when(orderRepo.findById(5L)).thenReturn(Optional.of(order));

        RequestReturnRequest request = requestFor(1L, 1);

        assertThrows(
                InvalidReturnException.class,
                () -> svc.requestReturn(5L, USER_ID, "USER", request)
        );
    }

    // ---- requestReturn: return window ----

    @Test
    void requestReturnAllowsAReturnWellWithinTheWindow() {
        Order order = deliveredOrder(Instant.now().minus(2, ChronoUnit.DAYS));
        when(orderRepo.findById(5L)).thenReturn(Optional.of(order));
        when(returnRepo.findByOrderIdAndStatusIn(eq(5L), any())).thenReturn(List.of());
        when(returnRepo.save(any(ReturnRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> svc.requestReturn(5L, USER_ID, "USER", requestFor(1L, 1)));
    }

    @Test
    void requestReturnRejectsAfterTheWindowHasExpired() {
        Order order = deliveredOrder(Instant.now().minus(8, ChronoUnit.DAYS));
        when(orderRepo.findById(5L)).thenReturn(Optional.of(order));

        RequestReturnRequest request = requestFor(1L, 1);

        assertThrows(
                InvalidReturnException.class,
                () -> svc.requestReturn(5L, USER_ID, "USER", request)
        );
    }

    // ---- requestReturn: quantity validation ----

    @Test
    void requestReturnRejectsAProductNotOnTheOrder() {
        Order order = deliveredOrder(Instant.now());
        when(orderRepo.findById(5L)).thenReturn(Optional.of(order));
        when(returnRepo.findByOrderIdAndStatusIn(eq(5L), any())).thenReturn(List.of());

        RequestReturnRequest request = requestFor(999L, 1);

        assertThrows(
                InvalidReturnException.class,
                () -> svc.requestReturn(5L, USER_ID, "USER", request)
        );
    }

    @Test
    void requestReturnRejectsMoreThanWasOrdered() {
        Order order = deliveredOrder(Instant.now());
        when(orderRepo.findById(5L)).thenReturn(Optional.of(order));
        when(returnRepo.findByOrderIdAndStatusIn(eq(5L), any())).thenReturn(List.of());

        // Order has 2 of product 1L; requesting 3 should fail.
        RequestReturnRequest request = requestFor(1L, 3);

        assertThrows(
                InvalidReturnException.class,
                () -> svc.requestReturn(5L, USER_ID, "USER", request)
        );
    }

    @Test
    void requestReturnAccountsForQuantityAlreadyReturned() {
        Order order = deliveredOrder(Instant.now());
        when(orderRepo.findById(5L)).thenReturn(Optional.of(order));

        // 1 of the 2 already returned via a prior REQUESTED return.
        ReturnRequest priorReturn = new ReturnRequest();
        priorReturn.setStatus(ReturnStatus.REQUESTED);
        priorReturn.addItem(new ReturnItem(1L, "Phone", 1, new BigDecimal("100.00")));
        when(returnRepo.findByOrderIdAndStatusIn(eq(5L), any())).thenReturn(List.of(priorReturn));

        // Only 1 remains eligible — requesting 2 more should fail.
        RequestReturnRequest request = requestFor(1L, 2);

        assertThrows(
                InvalidReturnException.class,
                () -> svc.requestReturn(5L, USER_ID, "USER", request)
        );
    }

    @Test
    void requestReturnComputesRefundAmountFromSnapshottedUnitPrice() {
        Order order = deliveredOrder(Instant.now());
        when(orderRepo.findById(5L)).thenReturn(Optional.of(order));
        when(returnRepo.findByOrderIdAndStatusIn(eq(5L), any())).thenReturn(List.of());
        when(returnRepo.save(any(ReturnRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        ReturnResponse resp = svc.requestReturn(5L, USER_ID, "USER", requestFor(1L, 2));

        assertEquals(new BigDecimal("200.00"), resp.getRefundAmount());
        assertEquals(ReturnStatus.REQUESTED, resp.getStatus());
    }

    // ---- approve ----

    private ReturnRequest requestedReturn(PaymentMethod method) {
        Order order = deliveredOrder(Instant.now());
        order.setPaymentMethod(method);
        ReturnRequest rr = new ReturnRequest();
        rr.setId(9L);
        rr.setOrder(order);
        rr.setUserId(USER_ID);
        rr.setStatus(ReturnStatus.REQUESTED);
        rr.setRefundAmount(new BigDecimal("200.00"));
        rr.addItem(new ReturnItem(1L, "Phone", 2, new BigDecimal("100.00")));
        return rr;
    }

    @Test
    void approveThrowsWhenReturnDoesNotExist() {
        when(returnRepo.findById(9L)).thenReturn(Optional.empty());

        assertThrows(ReturnRequestNotFoundException.class, () -> svc.approve(9L));
    }

    @Test
    void approveThrowsWhenAlreadyDecided() {
        ReturnRequest rr = requestedReturn(PaymentMethod.CARD);
        rr.setStatus(ReturnStatus.REFUNDED);
        when(returnRepo.findById(9L)).thenReturn(Optional.of(rr));

        assertThrows(InvalidReturnException.class, () -> svc.approve(9L));
    }

    @Test
    void approveRefundsAndRestocksForACardOrder() {
        ReturnRequest rr = requestedReturn(PaymentMethod.CARD);
        when(returnRepo.findById(9L)).thenReturn(Optional.of(rr));
        when(refundClient.refund(5L, new BigDecimal("200.00")))
                .thenReturn(new RefundClient.RefundOutcome("MOCK-REFUND-xyz"));
        when(returnRepo.save(any(ReturnRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        ReturnResponse resp = svc.approve(9L);

        assertEquals(ReturnStatus.REFUNDED, resp.getStatus());
        assertTrue(resp.getDecisionNote().contains("MOCK-REFUND-xyz"));
        verify(inventoryClient).adjust(1L, 2);
    }

    @Test
    void approveSkipsPaymentSvcEntirelyForCod() {
        ReturnRequest rr = requestedReturn(PaymentMethod.COD);
        when(returnRepo.findById(9L)).thenReturn(Optional.of(rr));
        when(returnRepo.save(any(ReturnRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        ReturnResponse resp = svc.approve(9L);

        assertEquals(ReturnStatus.REFUNDED, resp.getStatus());
        verifyNoInteractions(refundClient);
        verify(inventoryClient).adjust(1L, 2);
    }

    @Test
    void approveThrowsRefundFailedWhenPaymentSvcCallFails() {
        ReturnRequest rr = requestedReturn(PaymentMethod.CARD);
        when(returnRepo.findById(9L)).thenReturn(Optional.of(rr));
        when(refundClient.refund(anyLong(), any())).thenThrow(new RuntimeException("payment-svc unreachable"));

        assertThrows(RefundFailedException.class, () -> svc.approve(9L));
        // Nothing should be restocked or persisted if the refund itself never went through.
        verifyNoInteractions(inventoryClient);
        verify(returnRepo, never()).save(any());
    }

    // Added to document the accepted tradeoff described in ReturnSvc#approve's
    // Javadoc: a restock failure AFTER a successful refund must not make the
    // return look like it failed, since the money already moved.
    @Test
    void approveStillMarksRefundedWhenRestockFailsAfterASuccessfulRefund() {
        ReturnRequest rr = requestedReturn(PaymentMethod.CARD);
        when(returnRepo.findById(9L)).thenReturn(Optional.of(rr));
        when(refundClient.refund(5L, new BigDecimal("200.00")))
                .thenReturn(new RefundClient.RefundOutcome("MOCK-REFUND-xyz"));
        doThrow(new RuntimeException("inventory-svc unreachable")).when(inventoryClient).adjust(1L, 2);
        when(returnRepo.save(any(ReturnRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        ReturnResponse resp = svc.approve(9L);

        assertEquals(ReturnStatus.REFUNDED, resp.getStatus());
    }

    // ---- reject ----

    @Test
    void rejectSetsStatusAndDecisionNote() {
        ReturnRequest rr = requestedReturn(PaymentMethod.CARD);
        when(returnRepo.findById(9L)).thenReturn(Optional.of(rr));
        when(returnRepo.save(any(ReturnRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        RejectReturnRequest req = new RejectReturnRequest();
        req.setReason("Item shows signs of use");

        ReturnResponse resp = svc.reject(9L, req);

        assertEquals(ReturnStatus.REJECTED, resp.getStatus());
        assertEquals("Item shows signs of use", resp.getDecisionNote());
        verifyNoInteractions(refundClient, inventoryClient);
    }

    @Test
    void rejectThrowsWhenAlreadyDecided() {
        ReturnRequest rr = requestedReturn(PaymentMethod.CARD);
        rr.setStatus(ReturnStatus.REJECTED);
        when(returnRepo.findById(9L)).thenReturn(Optional.of(rr));

        RejectReturnRequest req = new RejectReturnRequest();
        req.setReason("Already handled");

        assertThrows(InvalidReturnException.class, () -> svc.reject(9L, req));
    }

    // ---- getOne / listMine ownership ----

    @Test
    void getOneRejectsNonOwnerNonAdmin() {
        ReturnRequest rr = requestedReturn(PaymentMethod.CARD);
        when(returnRepo.findById(9L)).thenReturn(Optional.of(rr));

        assertThrows(ForbiddenException.class, () -> svc.getOne(9L, 999L, "USER"));
    }

    @Test
    void getOneAllowsTheOwner() {
        ReturnRequest rr = requestedReturn(PaymentMethod.CARD);
        when(returnRepo.findById(9L)).thenReturn(Optional.of(rr));

        assertDoesNotThrow(() -> svc.getOne(9L, USER_ID, "USER"));
    }
}
