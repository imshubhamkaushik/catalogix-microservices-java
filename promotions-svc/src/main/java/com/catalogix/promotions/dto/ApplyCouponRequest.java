package com.catalogix.promotions.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class ApplyCouponRequest {
    @NotNull
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal subtotal;

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
}
