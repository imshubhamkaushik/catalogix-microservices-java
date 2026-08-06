package com.catalogix.payment.controller;

import com.catalogix.payment.dto.PaymentResponse;
import com.catalogix.payment.dto.ProcessPaymentRequest;
import com.catalogix.payment.svc.PaymentSvc;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Called synchronously and only by checkout-svc, on the request thread that
 * is finalizing an order — never by a browser directly (no gateway route is
 * exposed for this service). checkout-svc forwards the end user's own
 * bearer token; requestedByUserId below is that user, purely for audit —
 * this service does not re-check ownership of the order (checkout-svc, the
 * order's actual owner, has already authorized the request before calling us).
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
            HttpServletRequest request
    ) {
        Long userId = (Long) request.getAttribute("userId");
        PaymentResponse resp = svc.process(req, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }
}
