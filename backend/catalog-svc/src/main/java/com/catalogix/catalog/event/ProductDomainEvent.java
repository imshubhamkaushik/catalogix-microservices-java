package com.catalogix.catalog.event;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductDomainEvent(Long id, String name, String description, BigDecimal price, String category,
    Long ownerId, String imageUrl, String moderationStatus, boolean deleted, Instant occurredAt) {
}
