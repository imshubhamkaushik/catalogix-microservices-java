package com.catalogix.cart.dto;

import java.math.BigDecimal;

// Mirrors catalog-svc's ProductResponse shape (id/name/price/stockQuantity
// only — cart-svc doesn't need description/category/owner/createdAt).
public class ProductLookupResponse {
    private Long id;
    private String name;
    private BigDecimal price;
    private Integer stockQuantity;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public Integer getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }
}
