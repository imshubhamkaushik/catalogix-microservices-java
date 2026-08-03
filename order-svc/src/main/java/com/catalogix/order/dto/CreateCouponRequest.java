package com.catalogix.order.dto;

import com.catalogix.order.model.DiscountType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public class CreateCouponRequest {

    @NotBlank(message = "code is required")
    private String code;

    @NotNull(message = "discountType is required")
    private DiscountType discountType;

    @NotNull(message = "discountValue is required")
    @DecimalMin(value = "0.01", message = "discountValue must be greater than zero")
    private BigDecimal discountValue;

    @Min(value = 1, message = "maxUses must be at least 1 if provided")
    private Integer maxUses;

    private Instant expiresAt;

    public CreateCouponRequest() {}

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public DiscountType getDiscountType() { return discountType; }
    public void setDiscountType(DiscountType discountType) { this.discountType = discountType; }

    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }

    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
