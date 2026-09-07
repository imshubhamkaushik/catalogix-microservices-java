package com.catalogix.promotions.dto;

import java.math.BigDecimal;

public class DiscountResponse {
    private String code;
    private BigDecimal discountAmount;

    public DiscountResponse() {}
    public DiscountResponse(String code, BigDecimal discountAmount) {
        this.code = code;
        this.discountAmount = discountAmount;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
}
