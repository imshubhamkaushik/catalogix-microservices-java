package com.catalogix.catalog.event;

import com.catalogix.catalog.model.Product.ModerationStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record ProductDomainEvent(Long id, String name, String description, BigDecimal price, String category,
    Long ownerId, String imageUrl, String moderationStatus, boolean deleted, Instant occurredAt) {
}
