package com.catalogix.checkout.svc;

import com.catalogix.checkout.model.OrderStatus;
import com.catalogix.checkout.repository.OrderRepository;
import com.catalogix.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingOrderExpiryJobTest {

    private OrderRepository orders;
    private CheckoutSvc checkoutSvc;
    private JwtService jwtService;
    private PendingOrderExpiryJob job;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        checkoutSvc = mock(CheckoutSvc.class);
        jwtService = new JwtService("test-only-secret-at-least-32-characters-long");
        job = new PendingOrderExpiryJob(orders, checkoutSvc, jwtService, 30, 50);
    }

    @Test
    void expiresEveryStaleUnpaidOrder() {
        when(orders.findIdsByStatusCreatedBefore(eq(OrderStatus.PENDING_PAYMENT), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L));

        job.sweep();

        verify(checkoutSvc).expireUnpaidOrder(eq(1L), anyString());
        verify(checkoutSvc).expireUnpaidOrder(eq(2L), anyString());
    }

    @Test
    void oneFailingOrderDoesNotStopTheSweep() {
        when(orders.findIdsByStatusCreatedBefore(eq(OrderStatus.PENDING_PAYMENT), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L));
        doThrow(new RuntimeException("promotions-svc down")).when(checkoutSvc).expireUnpaidOrder(eq(1L), anyString());

        job.sweep();

        verify(checkoutSvc).expireUnpaidOrder(eq(2L), anyString());
    }

    @Test
    void doesNothingWhenNoOrderIsStale() {
        when(orders.findIdsByStatusCreatedBefore(eq(OrderStatus.PENDING_PAYMENT), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        job.sweep();

        verify(checkoutSvc, never()).expireUnpaidOrder(any(), anyString());
    }
}
