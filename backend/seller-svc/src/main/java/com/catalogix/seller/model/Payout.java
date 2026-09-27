package com.catalogix.seller.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "seller_payouts", indexes = @Index(name = "idx_payout_user", columnList = "seller_user_id"), uniqueConstraints = @UniqueConstraint(name = "uk_payout_idempotency", columnNames = "idempotency_key"))
public class Payout {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "seller_user_id", nullable = false)
    private Long sellerUserId;
    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status = PayoutStatus.REQUESTED;
    @Column(name = "reference", nullable = false, unique = true, length = 80)
    private String reference;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "completed_at")
    private Instant completedAt;

    public Payout() {
    }

    public Payout(Long user, BigDecimal amount, String ref, String idempotencyKey) {
        sellerUserId = user;
        this.amount = amount;
        reference = ref;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() {
        return id;
    }

    public Long getSellerUserId() {
        return sellerUserId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PayoutStatus getStatus() {
        return status;
    }

    public void setStatus(PayoutStatus v) {
        status = v;
    }

    public String getReference() {
        return reference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void complete() {
        status = PayoutStatus.COMPLETED;
        completedAt = Instant.now();
    }
}
