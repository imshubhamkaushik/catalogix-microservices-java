package com.catalogix.seller.event;

import java.time.Instant;
import java.util.List;

public record ReturnRefundedEvent(Long returnId, Long orderId, Long userId, List<OrderItemEventData> items, Instant occurredAt) {
    public ReturnRefundedEvent(Long returnId, Long orderId, Long userId, List<OrderItemEventData> items) {
        this(returnId, orderId, userId, items, Instant.now());
    }
}
