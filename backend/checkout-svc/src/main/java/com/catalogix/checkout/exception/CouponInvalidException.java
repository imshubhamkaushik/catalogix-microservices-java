package com.catalogix.checkout.exception;

// Raised when promotions-svc rejects a coupon at commit time (not found,
// expired, deactivated, or exhausted — possibly by a concurrent order that
// won the race promotions-svc's row lock resolves).
public class CouponInvalidException extends RuntimeException {
    public CouponInvalidException(String message) {
        super(message);
    }
}
