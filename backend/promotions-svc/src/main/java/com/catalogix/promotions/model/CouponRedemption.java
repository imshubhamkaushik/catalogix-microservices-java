package com.catalogix.promotions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** Durable idempotency record for one logical coupon redemption. */
@Entity
@Table(name = "coupon_redemptions")
public class CouponRedemption {

    @Id
    @Column(name = "operation_id", length = 160)
    private String operationId;

    @Column(name = "coupon_code", nullable = false, length = 50)
    private String couponCode;

    @Column(name = "subtotal_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "released_at")
    private Instant releasedAt;

    protected CouponRedemption() {}

    public CouponRedemption(String operationId, String couponCode, BigDecimal subtotal, BigDecimal discountAmount) {
        this.operationId = operationId;
        this.couponCode = couponCode;
        this.subtotal = subtotal;
        this.discountAmount = discountAmount;
    }

    public String getOperationId() { return operationId; }
    public String getCouponCode() { return couponCode; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }
}
