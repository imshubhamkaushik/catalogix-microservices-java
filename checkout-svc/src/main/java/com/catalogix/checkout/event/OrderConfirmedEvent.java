package com.catalogix.checkout.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Published (via Spring's ApplicationEventPublisher, then relayed to RabbitMQ
 * only after the DB transaction commits — see OrderEventPublisher) when an
 * order's payment succeeds. notification-svc consumes the routed RabbitMQ
 * message and turns it into an actual email; this class itself is just the
 * data, with no email-subject/body text baked in.
 */
public record OrderConfirmedEvent(
        Long orderId,
        String userEmail,
        List<OrderItemEventData> items,
        BigDecimal totalAmount,
        Instant occurredAt
) {
    public OrderConfirmedEvent(Long orderId, String userEmail, List<OrderItemEventData> items, BigDecimal totalAmount) {
        this(orderId, userEmail, items, totalAmount, Instant.now());
    }
}
