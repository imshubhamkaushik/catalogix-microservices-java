package com.catalogix.promotions.dto;

import com.catalogix.promotions.model.DiscountType;
import java.math.BigDecimal;
import java.time.Instant;

public class CouponResponse {
    private Long id;
    private String code;
    private DiscountType discountType;
    private BigDecimal discountValue;
    private Integer maxUses;
    private int usedCount;
    private Instant expiresAt;
    private boolean active;
    private Instant createdAt;

    public CouponResponse() {}
    public CouponResponse(Long id, String code, DiscountType discountType, BigDecimal discountValue,
                           Integer maxUses, int usedCount, Instant expiresAt, boolean active, Instant createdAt) {
        this.id = id; this.code = code; this.discountType = discountType; this.discountValue = discountValue;
        this.maxUses = maxUses; this.usedCount = usedCount; this.expiresAt = expiresAt; this.active = active;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public DiscountType getDiscountType() { return discountType; }
    public void setDiscountType(DiscountType discountType) { this.discountType = discountType; }
    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }
    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }
    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
