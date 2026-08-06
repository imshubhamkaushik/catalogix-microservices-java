package com.catalogix.checkout.model;

public enum CompensationType {
    // Restore stock at inventory-svc (positive delta against the product).
    RELEASE_STOCK,
    // Give back one use of a coupon at promotions-svc.
    RELEASE_COUPON
}
