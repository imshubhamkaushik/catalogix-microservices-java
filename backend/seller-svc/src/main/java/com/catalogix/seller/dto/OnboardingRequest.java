package com.catalogix.seller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OnboardingRequest(@NotBlank @Size(max = 120) String displayName, @Size(max = 160) String businessName,
    @Size(max = 500) String description) {
}
