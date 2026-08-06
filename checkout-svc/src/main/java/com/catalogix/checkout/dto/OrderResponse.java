package com.catalogix.checkout.dto;

import com.catalogix.checkout.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class OrderResponse {
    private Long id;
    private Long userId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private Instant createdAt;
    private List<OrderItemResponse> items;
    private String appliedCouponCode;
    private BigDecimal discountAmount;

    public OrderResponse() {
    }

    // Kept for existing callers that don't care about coupons (defaults to no
    // discount); prefer the 8-arg constructor for real use.
    public OrderResponse(Long id, Long userId, OrderStatus status, BigDecimal totalAmount,
                          Instant createdAt, List<OrderItemResponse> items) {
        this(id, userId, status, totalAmount, createdAt, items, null, BigDecimal.ZERO);
    }

    public OrderResponse(Long id, Long userId, OrderStatus status, BigDecimal totalAmount,
                          Instant createdAt, List<OrderItemResponse> items,
                          String appliedCouponCode, BigDecimal discountAmount) {
        this.id = id;
        this.userId = userId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
        this.items = items;
        this.appliedCouponCode = appliedCouponCode;
        this.discountAmount = discountAmount;
    }

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public OrderStatus getStatus() {
        return status;
    }
    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<OrderItemResponse> getItems() {
        return items;
    }
    public void setItems(List<OrderItemResponse> items) {
        this.items = items;
    }

    public String getAppliedCouponCode() {
        return appliedCouponCode;
    }
    public void setAppliedCouponCode(String appliedCouponCode) {
        this.appliedCouponCode = appliedCouponCode;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }
    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }
}
