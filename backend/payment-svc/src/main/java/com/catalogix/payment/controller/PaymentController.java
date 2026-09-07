package com.catalogix.payment.controller;

import com.catalogix.payment.dto.PaymentResponse;
import com.catalogix.payment.dto.ProcessPaymentRequest;
import com.catalogix.payment.dto.ProcessRefundRequest;
import com.catalogix.payment.dto.RefundResponse;
import com.catalogix.payment.exception.ForbiddenException;
import com.catalogix.payment.svc.PaymentSvc;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Called synchronously and only by checkout-svc, on the request thread that
 * is finalizing an order (or, for /refund, processing an approved return) —
 * never by a browser directly (no gateway route is exposed for this service).
 *
 * SECURITY FIX: this used to accept any authenticated user's regular token
 * and derived requestedByUserId from it — meaning any logged-in user could
 * call this directly with an arbitrary orderId/amount and have it recorded
 * as a genuine payment attempt, since this service (by design) never
 * re-checks order ownership itself. It now requires a SYSTEM-role token
 * (checkout-svc mints one per call — see its PaymentClient) and reads
 * requestedByUserId explicitly from the body instead, since a system
 * token's own subject is a sentinel, not a real user.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentSvc svc;

    public PaymentController(PaymentSvc svc) {
        this.svc = svc;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> process(
            @Valid @RequestBody ProcessPaymentRequest req,
            @RequestAttribute("userRole") String role
    ) {
        if (!"SYSTEM".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Payments must be initiated by checkout-svc, not called directly");
        }
        PaymentResponse resp = svc.process(req, req.getRequestedByUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    // Same SYSTEM-only lockdown as /payments above — checkout-svc is the
    // only legitimate caller, only when an admin approves a return (see
    // checkout-svc's ReturnSvc), and only for CARD/UPI orders (COD never
    // reaches this at all, since there's nothing to refund).
    @PostMapping("/refund")
    public ResponseEntity<RefundResponse> refund(
            @Valid @RequestBody ProcessRefundRequest req,
            @RequestAttribute("userRole") String role
    ) {
        if (!"SYSTEM".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Refunds must be initiated by checkout-svc, not called directly");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(svc.refund(req));
    }
}
