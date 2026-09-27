package com.catalogix.fulfillment.dto;

import com.catalogix.fulfillment.model.ShipmentStatus;
import jakarta.validation.constraints.NotNull;

public record StatusRequest(@NotNull ShipmentStatus status) {
}
