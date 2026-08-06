package com.catalogix.cart.dto;

import java.math.BigDecimal;

// From promotions-svc's GET /promotions/{code}/preview — read-only, doesn't
// consume a redemption. Used only to show the discount in the cart view.
public class CouponPreviewResponse {
    private BigDecimal discountAmount;

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
}
