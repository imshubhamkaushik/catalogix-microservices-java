package com.catalogix.order.event;

import java.time.Instant;

public record OrderCancelledEvent(Long orderId, String userEmail, Instant occurredAt) {
    public OrderCancelledEvent(Long orderId, String userEmail) {
        this(orderId, userEmail, Instant.now());
    }
}
