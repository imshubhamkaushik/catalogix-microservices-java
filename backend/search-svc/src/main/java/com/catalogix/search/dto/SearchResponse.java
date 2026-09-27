package com.catalogix.search.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SearchResponse(Long id, String name, String description, BigDecimal price, String category, Long ownerId,
    String imageUrl, Instant updatedAt) {
}
