package com.catalogix.inventory.model;

import jakarta.persistence.*;

/**
 * Owns exactly one thing: how many units of a product are available. Split
 * out of the old product-svc Product entity on purpose — "what a product
 * is" (name/description/price, catalog-svc's job) changes rarely and is
 * read constantly; "how many are left" changes on every order and needs a
 * much stronger consistency story (see findByProductIdForUpdate below).
 * Bundling the two in one table/service was the thing worth un-bundling.
 */
@Entity
@Table(name = "stock_items")
public class StockItem {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    public StockItem() {}

    public StockItem(Long productId, int quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
