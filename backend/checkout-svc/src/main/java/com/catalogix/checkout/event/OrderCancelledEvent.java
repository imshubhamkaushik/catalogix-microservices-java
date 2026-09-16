package com.catalogix.checkout.event;

import java.time.Instant;

// See OrderConfirmedEvent's Javadoc for why userId was added alongside
// userEmail.
public record OrderCancelledEvent(Long orderId, Long userId, String userEmail, Instant occurredAt) {
    public OrderCancelledEvent(Long orderId, Long userId, String userEmail) {
        this(orderId, userId, userEmail, Instant.now());
    }
}
