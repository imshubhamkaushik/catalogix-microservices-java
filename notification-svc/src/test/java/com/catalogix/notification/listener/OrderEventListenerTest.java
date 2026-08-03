package com.catalogix.notification.listener;

import com.catalogix.notification.event.OrderCancelledEvent;
import com.catalogix.notification.event.OrderConfirmedEvent;
import com.catalogix.notification.event.OrderItemEventData;
import com.catalogix.notification.svc.EmailSvc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderEventListenerTest {

    @Mock private EmailSvc emailSvc;

    private OrderEventListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new OrderEventListener(emailSvc);
    }

    @Test
    @SuppressWarnings("null")
    void onOrderConfirmedBuildsAnEmailListingEachItemAndTheTotal() {
        OrderConfirmedEvent event = new OrderConfirmedEvent(
                5L, "buyer@example.com",
                List.of(new OrderItemEventData("Phone", 2, new BigDecimal("100.00"), new BigDecimal("200.00"))),
                new BigDecimal("200.00"), null);

        listener.onOrderConfirmed(event);

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSvc).send(eq("buyer@example.com"), subjectCaptor.capture(), bodyCaptor.capture());

        assertTrue(subjectCaptor.getValue().contains("#5"));
        assertTrue(bodyCaptor.getValue().contains("Phone"));
        assertTrue(bodyCaptor.getValue().contains("200.00"));
    }

    @Test
    void onOrderConfirmedSkipsWhenNoEmailPresent() {
        OrderConfirmedEvent event = new OrderConfirmedEvent(5L, "", List.of(), BigDecimal.ZERO, null);
        listener.onOrderConfirmed(event);
        verifyNoInteractions(emailSvc);
    }

    @Test
    void onOrderCancelledBuildsACancellationEmail() {
        OrderCancelledEvent event = new OrderCancelledEvent(5L, "buyer@example.com", null);

        listener.onOrderCancelled(event);

        verify(emailSvc).send(eq("buyer@example.com"), contains("#5"), contains("cancelled"));
    }
}
