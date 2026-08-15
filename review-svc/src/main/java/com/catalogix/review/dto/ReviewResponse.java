package com.catalogix.review.dto;

import com.catalogix.review.model.Review;
import java.time.Instant;

public class ReviewResponse {
    private Long id;
    private Long productId;
    private Long userId;
    private String reviewerEmail;
    private Integer rating;
    private String title;
    private String body;
    private boolean verifiedPurchase;
    private Instant createdAt;

    public ReviewResponse() {}

    public static ReviewResponse from(Review r) {
        ReviewResponse resp = new ReviewResponse();
        resp.id = r.getId();
        resp.productId = r.getProductId();
        resp.userId = r.getUserId();
        resp.reviewerEmail = r.getReviewerEmail();
        resp.rating = r.getRating();
        resp.title = r.getTitle();
        resp.body = r.getBody();
        resp.verifiedPurchase = r.isVerifiedPurchase();
        resp.createdAt = r.getCreatedAt();
        return resp;
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
