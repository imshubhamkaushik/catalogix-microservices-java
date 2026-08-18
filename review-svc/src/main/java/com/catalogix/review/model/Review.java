package com.catalogix.review.model;

import jakarta.persistence.*;
import java.time.Instant;

// One review per (userId, productId) — enforced by uq_reviews_user_product
// (see migration). "Submit a review" is create-OR-update: writing a second
// review for the same product replaces the first rather than erroring or
// creating a duplicate, which is what Amazon/Flipkart's own "edit your
// review" flow amounts to anyway — no separate PUT endpoint needed for it.
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Snapshotted from the JWT's email claim at submission time — same
    // "capture now, don't live-reference" reasoning as Order's shipping
    // address: a reviewer's email is display context, not something a
    // review needs to track live if they ever change it.
    @Column(name = "reviewer_email", nullable = false, length = 255)
    private String reviewerEmail;

    @Column(nullable = false)
    private Integer rating;

    @Column(length = 120)
    private String title;

    @Column(length = 2000)
    private String body;

    // True only if, at submission time, the reviewer had a DELIVERED order
    // containing this product (see OrderClient). Snapshotted rather than
    // recomputed on every read: a review that was verified when written
    // stays verified even if, say, the order later gets returned — matching
    // how the badge behaves on real storefronts.
    @Column(name = "verified_purchase", nullable = false)
    private boolean verifiedPurchase;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Review() {
        // JPA only
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getReviewerEmail() { return reviewerEmail; }
    public void setReviewerEmail(String reviewerEmail) { this.reviewerEmail = reviewerEmail; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public boolean isVerifiedPurchase() { return verifiedPurchase; }
    public void setVerifiedPurchase(boolean verifiedPurchase) { this.verifiedPurchase = verifiedPurchase; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
