package com.catalogix.checkout.model;

// Lifecycle: PENDING_PAYMENT -> (PAYMENT_PROCESSING) -> CONFIRMED -> SHIPPED -> DELIVERED
// CANCELLED is reachable from PENDING_PAYMENT or CONFIRMED only — once an
// order has shipped, "cancelling" it is a returns/refunds problem, which is
// out of scope here.
//
// PAYMENT_PROCESSING is a short-lived claim, held only while checkout-svc waits
// for payment-svc. It replaces a row lock (SELECT ... FOR UPDATE) that used to be
// held across that HTTP call: the status IS the mutual exclusion, so a second
// pay/cancel attempt is rejected immediately instead of pinning a DB connection.
// It always ends in CONFIRMED / CANCELLED, or back in PENDING_PAYMENT if the
// outcome was unknown (see CheckoutSvc#payOrder and PendingOrderExpiryJob).
public enum OrderStatus {
    PENDING_PAYMENT,
    PAYMENT_PROCESSING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
