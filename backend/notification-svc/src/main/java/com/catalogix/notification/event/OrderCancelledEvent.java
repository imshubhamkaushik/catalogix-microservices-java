package com.catalogix.notification.event;

import java.time.Instant;

public record OrderCancelledEvent(Long orderId, Long userId, String userEmail, Instant occurredAt) {
}
