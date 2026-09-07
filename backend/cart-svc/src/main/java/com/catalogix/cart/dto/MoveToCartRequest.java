package com.catalogix.cart.dto;

import jakarta.validation.constraints.Min;

// Quantity is optional — defaults to 1, matching the one-click "Move to
// Cart" button real storefronts use; a caller that wants more can still say so.
public class MoveToCartRequest {
    @Min(value = 1, message = "quantity must be at least 1")
    private Integer quantity = 1;

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}
