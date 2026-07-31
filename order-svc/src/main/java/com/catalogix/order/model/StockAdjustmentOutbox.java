package com.catalogix.order.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Outbox entry for a stock adjustment that needs to reach product-svc
 * "eventually" even if it can't happen right now — used for restocking on
 * order cancellation and for compensating an earlier line item when a later
 * one in the same order fails. Written in the same DB transaction as the
 * order-status change it accompanies, so the intent to restock is never lost
 * even if product-svc is unreachable at that exact moment: a scheduled
 * processor retries PENDING rows until they succeed or exhaust their retries.
 */
@Entity
@Table(name = "stock_adjustment_outbox")
public class StockAdjustmentOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    // Positive = restock, negative = reserve. Stored as originally intended;
    // the processor just replays this exact delta against product-svc.
    @Column(nullable = false)
    private Integer delta;

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

    public StockAdjustmentOutbox() {
    }

    public StockAdjustmentOutbox(Long productId, Integer delta, String reason) {
        this.productId = productId;
        this.delta = delta;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

    public Long getProductId() {
        return productId;
    }
    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getDelta() {
        return delta;
    }
    public void setDelta(Integer delta) {
        this.delta = delta;
    }

    public String getReason() {
        return reason;
    }
    public void setReason(String reason) {
        this.reason = reason;
    }

    public OutboxStatus getStatus() {
        return status;
    }
    public void setStatus(OutboxStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }
    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
