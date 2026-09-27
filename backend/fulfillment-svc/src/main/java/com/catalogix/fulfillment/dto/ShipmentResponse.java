package com.catalogix.fulfillment.dto;

import com.catalogix.fulfillment.model.ShipmentStatus;
import java.time.Instant;

public record ShipmentResponse(Long id, Long orderId, Long sellerId, String trackingNumber, ShipmentStatus status,
    Instant createdAt, Instant updatedAt) {
}
