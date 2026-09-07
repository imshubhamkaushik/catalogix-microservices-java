package com.catalogix.notification.event;

import java.time.Instant;

public record OrderCancelledEvent(Long orderId, String userEmail, Instant occurredAt) {
}
