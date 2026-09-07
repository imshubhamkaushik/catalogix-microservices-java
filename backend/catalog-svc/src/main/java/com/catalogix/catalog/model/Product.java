package com.catalogix.catalog.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

// Owns what a product IS — name, description, price, category, owner.
// Deliberately does NOT own stock_quantity anymore: that field moved to
// inventory-svc's stock_items table. "What a product is" changes rarely and
// is read constantly (heavy caching, see CacheConfig); "how many are left"
// changes on every order and needs its own consistency story (row locking).
// Bundling the two was the split this service used to need and no longer does.
@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private String category = "GENERAL";

    // Id of the user (from user-svc) who created this listing. Only this
    // user, or an ADMIN, may delete it.
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Product() {}

    public Product(String name, String description, BigDecimal price) {
        this.name = name;
        this.description = description;
        this.price = price;
    }

    public Product(String name, String description, BigDecimal price, String category, Long ownerId) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.ownerId = ownerId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
