package com.catalogix.order.dto;

import jakarta.validation.constraints.NotBlank;

public class ApplyCouponRequest {

    @NotBlank(message = "code is required")
    private String code;

    public ApplyCouponRequest() {}

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}
