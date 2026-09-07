package com.catalogix.checkout.dto;

import java.math.BigDecimal;

public record ReturnItemResponse(Long productId, String productName, Integer quantity,
                                  BigDecimal unitPrice, BigDecimal subtotal) {}
