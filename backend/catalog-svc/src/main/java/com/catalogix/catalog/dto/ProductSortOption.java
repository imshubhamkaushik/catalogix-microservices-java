package com.catalogix.catalog.dto;

import org.springframework.data.domain.Sort;

// Friendly, Amazon/Flipkart-style sort names for GET /products?sortBy=...
// Spring Data's own ?sort=price,asc syntax already works out of the box on
// a Pageable-backed @Query (it appends the ORDER BY automatically) — this
// enum exists purely so the API has discoverable, typo-proof values instead
// of expecting callers to know Spring's raw sort-param syntax and the
// underlying JPA property names.
public enum ProductSortOption {
    PRICE_LOW_TO_HIGH(Sort.by(Sort.Direction.ASC, "price")),
    PRICE_HIGH_TO_LOW(Sort.by(Sort.Direction.DESC, "price")),
    NEWEST(Sort.by(Sort.Direction.DESC, "createdAt")),
    NAME_A_TO_Z(Sort.by(Sort.Direction.ASC, "name"));

    private final Sort sort;

    ProductSortOption(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
