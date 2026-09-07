package com.catalogix.cart.dto;

import jakarta.validation.constraints.NotNull;

public class AddWishlistItemRequest {
    @NotNull(message = "productId is required")
    private Long productId;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
}
