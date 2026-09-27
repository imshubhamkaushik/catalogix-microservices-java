package com.catalogix.seller.event;

import java.time.Instant;
import java.util.List;

public record OrderCancelledEvent(Long orderId, Long userId, String userEmail, List<OrderItemEventData> items,
        Instant occurredAt) {
    public OrderCancelledEvent(Long orderId, Long userId, String userEmail, List<OrderItemEventData> items) {
        this(orderId, userId, userEmail, items, Instant.now());
    }
}
