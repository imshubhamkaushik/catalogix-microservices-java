package com.catalogix.search.event;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductEvent(Long id, String name, String description, BigDecimal price, String category, Long ownerId,
        String imageUrl, String moderationStatus, Instant occurredAt, boolean deleted) {
}
