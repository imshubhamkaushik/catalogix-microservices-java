package com.catalogix.cart.dto;

import java.math.BigDecimal;
import java.time.Instant;

public class WishlistItemResponse {
    private Long productId;
    private String productName;
    private BigDecimal price;
    private Integer stockQuantity;
    private Instant addedAt;

    public WishlistItemResponse() {}

    public WishlistItemResponse(Long productId, String productName, BigDecimal price,
                                 Integer stockQuantity, Instant addedAt) {
        this.productId = productId;
        this.productName = productName;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.addedAt = addedAt;
    }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Integer getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }
    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }
}
