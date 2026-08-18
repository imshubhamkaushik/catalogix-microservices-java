package com.catalogix.checkout.dto;

import com.catalogix.checkout.model.ReturnStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class ReturnResponse {

    private Long id;
    private Long orderId;
    private Long userId;
    private String reason;
    private ReturnStatus status;
    private BigDecimal refundAmount;
    private String decisionNote;
    private Instant createdAt;
    private Instant decidedAt;
    private List<ReturnItemResponse> items;

    public ReturnResponse() {
        // Required for framework deserialization.
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public void setStatus(ReturnStatus status) {
        this.status = status;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public List<ReturnItemResponse> getItems() {
        return items;
    }

    public void setItems(List<ReturnItemResponse> items) {
        this.items = items;
    }
}