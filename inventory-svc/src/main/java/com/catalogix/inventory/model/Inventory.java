package com.catalogix.inventory.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One row per product. productId is the primary key here on purpose —
 * inventory is a 1:1 satellite of catalog-svc's product, not an independent
 * entity with its own identity. There is deliberately NO foreign key
 * constraint to catalog-svc's products table: it lives in a different
 * database now, so referential integrity across that boundary is enforced
 * by catalog-svc only ever creating a row here right after it creates the
 * product (see InventorySvc#initialize and catalog-svc's InventoryClient).
 */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Inventory() {}

    public Inventory(Long productId, Integer stockQuantity) {
        this.productId = productId;
        this.stockQuantity = stockQuantity;
    }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
