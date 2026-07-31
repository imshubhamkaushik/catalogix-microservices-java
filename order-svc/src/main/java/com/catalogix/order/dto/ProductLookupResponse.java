package com.catalogix.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Mirrors the subset of product-svc's ProductResponse that order-svc needs
 * when pricing/validating an order. Deserialized from GET {product-svc}/products/{id}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductLookupResponse {
    private Long id;
    private String name;
    private BigDecimal price;
    private Integer stockQuantity;

    public ProductLookupResponse() {
    }

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }
    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStockQuantity() {
        return stockQuantity;
    }
    public void setStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity;
    }
}
