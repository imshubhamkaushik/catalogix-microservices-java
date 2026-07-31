package com.catalogix.order.dto;

import com.catalogix.order.model.OutboxStatus;
import java.time.Instant;

public class OutboxEntryResponse {
    private Long id;
    private Long productId;
    private Integer delta;
    private String reason;
    private OutboxStatus status;
    private int attempts;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;

    public OutboxEntryResponse() {}

    public OutboxEntryResponse(Long id, Long productId, Integer delta, String reason, OutboxStatus status,
                                int attempts, String lastError, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.productId = productId;
        this.delta = delta;
        this.reason = reason;
        this.status = status;
        this.attempts = attempts;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getDelta() { return delta; }
    public void setDelta(Integer delta) { this.delta = delta; }

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
