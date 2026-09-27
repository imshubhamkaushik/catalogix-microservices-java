package com.catalogix.seller.dto;
import com.catalogix.seller.model.SellerStatus; import jakarta.validation.constraints.NotNull;
public record StatusRequest(@NotNull SellerStatus status) {}
