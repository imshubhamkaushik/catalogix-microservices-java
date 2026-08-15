package com.catalogix.review.dto;

import java.math.BigDecimal;

// averageRating is null (not zero) when reviewCount is 0 — lets a caller
// distinguish "no reviews yet" from "reviews exist and average out to 0",
// which can't actually happen (rating is 1-5) but null-for-empty is still
// the honest signal rather than a magic 0.
public record RatingSummaryResponse(Long productId, BigDecimal averageRating, long reviewCount) {}
