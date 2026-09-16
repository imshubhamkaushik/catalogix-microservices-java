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
 *
 * userId (added alongside userEmail, not replacing it — the email is still
 * what the message actually gets sent to) lets notification-svc look up the
 * recipient's notification preferences before deciding whether to send at
 * all. See UserSvc.updateNotificationPreferences's Javadoc on the user-svc
 * side for the full story on why this was added later, not from the start.
 */
public record OrderConfirmedEvent(
        Long orderId,
        Long userId,
        String userEmail,
        List<OrderItemEventData> items,
        BigDecimal totalAmount,
        Instant occurredAt
) {
    public OrderConfirmedEvent(Long orderId, Long userId, String userEmail, List<OrderItemEventData> items, BigDecimal totalAmount) {
        this(orderId, userId, userEmail, items, totalAmount, Instant.now());
    }
}
