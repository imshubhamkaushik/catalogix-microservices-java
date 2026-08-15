package com.catalogix.cart.model;

import jakarta.persistence.*;
import java.time.Instant;

// A saved-for-later product reference — the Amazon/Flipkart "heart" icon.
// Deliberately its own table rather than a flag on CartItem: a wishlist
// entry has no quantity, isn't part of checkout, and its lifecycle (added
// once, removed once, optionally moved to the cart) doesn't overlap with a
// cart line's at all beyond both referencing a productId.
@Entity
@Table(name = "wishlist_items")
public class WishlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt = Instant.now();

    public WishlistItem() {}

    public WishlistItem(Long userId, Long productId) {
        this.userId = userId;
        this.productId = productId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }
}
