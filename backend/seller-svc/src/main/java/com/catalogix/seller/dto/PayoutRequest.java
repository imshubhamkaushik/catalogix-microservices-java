package com.catalogix.seller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PayoutRequest(
    @NotNull @DecimalMin(value = "1.00") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
}
