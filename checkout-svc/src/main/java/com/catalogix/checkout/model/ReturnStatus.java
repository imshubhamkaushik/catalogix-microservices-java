package com.catalogix.checkout.model;

// REQUESTED -> REFUNDED (admin approves) or REJECTED (admin declines).
// No separate "APPROVED" state: approval and the actual refund/restock
// happen in the same admin action (see ReturnSvc#approve) rather than as
// two visible steps, since nothing in this app needs to observe "approved
// but not yet refunded" as its own moment.
public enum ReturnStatus {
    REQUESTED,
    REFUNDED,
    REJECTED
}
