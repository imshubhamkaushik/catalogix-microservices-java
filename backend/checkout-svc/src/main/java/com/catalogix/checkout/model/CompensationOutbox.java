package com.catalogix.checkout.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Generalized from the original stock_adjustment_outbox: this system now has
 * two kinds of compensating action that need to reach a downstream service
 * "eventually" even if it can't happen right now — releasing reserved stock
 * (inventory-svc) and releasing a redeemed coupon use (promotions-svc).
 * Written in the same DB transaction as the order-status change it
 * accompanies, so the intent to compensate is never lost even if the
 * downstream service is unreachable at that exact moment.
 */
@Entity
@Table(name = "compensation_outbox")
public class CompensationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompensationType type;

    // Populated for RELEASE_STOCK, null for RELEASE_COUPON.
    @Column(name = "product_id")
    private Long productId;

    @Column
    private Integer delta;

    // Populated for RELEASE_COUPON, null for RELEASE_STOCK.
    @Column(name = "coupon_code", length = 50)
    private String couponCode;

    @Column(length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public CompensationOutbox() {}

    public static CompensationOutbox releaseStock(Long productId, Integer delta, String reason) {
        CompensationOutbox e = new CompensationOutbox();
        e.type = CompensationType.RELEASE_STOCK;
        e.productId = productId;
        e.delta = delta;
        e.reason = reason;
        return e;
    }

    public static CompensationOutbox releaseCoupon(String couponCode, String reason) {
        CompensationOutbox e = new CompensationOutbox();
        e.type = CompensationType.RELEASE_COUPON;
        e.couponCode = couponCode;
        e.reason = reason;
        return e;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public CompensationType getType() { return type; }
    public void setType(CompensationType type) { this.type = type; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Integer getDelta() { return delta; }
    public void setDelta(Integer delta) { this.delta = delta; }
    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public OutboxStatus getStatus() { return status; }
    public void setStatus(OutboxStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
