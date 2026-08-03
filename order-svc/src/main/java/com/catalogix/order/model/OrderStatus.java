package com.catalogix.order.model;

// Lifecycle: PENDING_PAYMENT -> CONFIRMED -> SHIPPED -> DELIVERED
// CANCELLED is reachable from PENDING_PAYMENT or CONFIRMED only — once an
// order has shipped, "cancelling" it is a returns/refunds problem, which is
// out of scope here.
public enum OrderStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
