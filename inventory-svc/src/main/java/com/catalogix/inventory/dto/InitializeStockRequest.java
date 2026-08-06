package com.catalogix.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class InitializeStockRequest {
    @NotNull(message = "productId is required")
    private Long productId;

    @NotNull(message = "initialQuantity is required")
    @Min(value = 0, message = "initialQuantity cannot be negative")
    private Integer initialQuantity;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getInitialQuantity() { return initialQuantity; }
    public void setInitialQuantity(Integer initialQuantity) { this.initialQuantity = initialQuantity; }
}
