package com.catalogix.cart.dto;

import jakarta.validation.constraints.NotBlank;

public class ApplyCouponRequest {
    @NotBlank(message = "code is required")
    private String code;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}
