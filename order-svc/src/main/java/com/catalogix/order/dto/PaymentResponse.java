package com.catalogix.order.dto;

import com.catalogix.order.model.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public class PaymentResponse {
    private Long id;
    private Long orderId;
    private BigDecimal amount;
    private String method;
    private PaymentStatus status;
    private String reference;
    private Instant createdAt;

    public PaymentResponse() {}

    public PaymentResponse(Long id, Long orderId, BigDecimal amount, String method,
                            PaymentStatus status, String reference, Instant createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.method = method;
        this.status = status;
        this.reference = reference;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
