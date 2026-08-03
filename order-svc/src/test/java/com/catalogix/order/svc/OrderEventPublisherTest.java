package com.catalogix.order.svc;

import com.catalogix.order.config.RabbitMQConfig;
import com.catalogix.order.event.OrderCancelledEvent;
import com.catalogix.order.event.OrderConfirmedEvent;
import com.catalogix.order.event.OrderItemEventData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderEventPublisherTest {

    @Mock private RabbitTemplate rabbitTemplate;

    private OrderEventPublisher publisher;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        publisher = new OrderEventPublisher(rabbitTemplate);
    }

    @Test
    void onOrderConfirmedPublishesToTheEventsExchangeWithTheRightRoutingKey() {
        OrderConfirmedEvent event = new OrderConfirmedEvent(
                5L, "buyer@example.com",
                List.of(new OrderItemEventData("Phone", 2, new BigDecimal("100.00"), new BigDecimal("200.00"))),
                new BigDecimal("200.00"));

        publisher.onOrderConfirmed(event);

        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, "order.confirmed", event);
    }

    @Test
    void onOrderCancelledPublishesToTheEventsExchangeWithTheRightRoutingKey() {
        OrderCancelledEvent event = new OrderCancelledEvent(5L, "buyer@example.com");

        publisher.onOrderCancelled(event);

        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, "order.cancelled", event);
    }

    @Test
    void aBrokerFailureDuringPublishIsSwallowedNotThrown() {
        OrderConfirmedEvent event = new OrderConfirmedEvent(5L, "buyer@example.com", List.of(), BigDecimal.ZERO);
        doThrow(new RuntimeException("broker unreachable"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Must not propagate — a notification-relay failure should never surface as an
        // application error (there's nothing meaningful for a caller to do about it here).
        publisher.onOrderConfirmed(event);
    }
}
