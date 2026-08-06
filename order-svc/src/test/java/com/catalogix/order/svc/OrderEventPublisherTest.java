package com.catalogix.order.svc;

import com.catalogix.order.config.RabbitMQConfig;
import com.catalogix.order.event.OrderCancelledEvent;
import com.catalogix.order.event.OrderConfirmedEvent;
import com.catalogix.order.event.OrderItemEventData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("null")
class OrderEventPublisherTest {

    @Mock private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OrderEventPublisher publisher;

    private static final String EMAIL = "buyer@example.com";
    private static final String PHONE = "Phone";
    private static final String PRICE_100 = "100.00";
    private static final String PRICE_200 = "200.00";
    private static final Long ORDER_ID = 5L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void onOrderConfirmedPublishesToTheEventsExchangeWithTheRightRoutingKey() {
        OrderConfirmedEvent event = new OrderConfirmedEvent(
                ORDER_ID, EMAIL,
                List.of(new OrderItemEventData(PHONE, 2, new BigDecimal(PRICE_100), new BigDecimal(PRICE_200))),
                new BigDecimal(PRICE_200));

        publisher.onOrderConfirmed(event);

        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, "order.confirmed", event);
    }

    @Test
    void onOrderCancelledPublishesToTheEventsExchangeWithTheRightRoutingKey() {
        OrderCancelledEvent event = new OrderCancelledEvent(ORDER_ID, EMAIL);

        publisher.onOrderCancelled(event);

        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, "order.cancelled", event);
    }

    @Test
    void aBrokerFailureDuringPublishIsSwallowedNotThrown() {
        OrderConfirmedEvent event = new OrderConfirmedEvent(ORDER_ID, EMAIL, List.of(), BigDecimal.ZERO);
        doThrow(new RuntimeException("broker unreachable"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Explicitly assert that the method completes without propagating the exception
        assertDoesNotThrow(() -> publisher.onOrderConfirmed(event));
    }
}