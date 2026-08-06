package com.catalogix.catalog.dto;

import jakarta.validation.constraints.NotNull;

// A positive delta restocks, a negative delta sells/reserves stock.
// Used both by the "Add stock" admin action and internally by order-svc
// when an order is placed (negative) or cancelled (positive, to restock).
public class StockAdjustmentRequest {

    @NotNull(message = "delta is required")
    private Integer delta;

    public StockAdjustmentRequest() {
    }

    public StockAdjustmentRequest(Integer delta) {
        this.delta = delta;
    }

    public Integer getDelta() {
        return delta;
    }

    public void setDelta(Integer delta) {
        this.delta = delta;
    }
}
