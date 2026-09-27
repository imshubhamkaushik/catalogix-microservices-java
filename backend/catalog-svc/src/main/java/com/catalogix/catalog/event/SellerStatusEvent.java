package com.catalogix.catalog.event;

import java.time.Instant;

public record SellerStatusEvent(
        String action,
        Long userId,
        Long sellerId,
        String status,
        Instant occurredAt
) {}
