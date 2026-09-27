package com.catalogix.seller.controller;

import com.catalogix.seller.dto.*;
import com.catalogix.seller.exception.ForbiddenException;
import com.catalogix.seller.model.SellerStatus;
import com.catalogix.seller.svc.SellerSvc;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/sellers")
public class SellerController {

    private final SellerSvc svc;

    public SellerController(SellerSvc s) {
        svc = s;
    }

    @GetMapping("/me")
    public ResponseEntity<SellerSummary> me(@RequestAttribute Long userId) {
        SellerSummary x = svc.me(userId);
        return x == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(x);
    }

    @PostMapping("/me/onboarding")
    public SellerSummary onboard(@RequestAttribute Long userId, @RequestAttribute String userRole,
            @Valid @RequestBody OnboardingRequest req) {
        ensureSeller(userRole);
        return svc.onboard(userId, req);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<SellerSummary> byUser(@PathVariable Long userId, @RequestAttribute("userId") Long requesterId,
            @RequestAttribute String userRole) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !userId.equals(requesterId)) {
            throw new ForbiddenException("Seller profile access denied");

        }
        SellerSummary x = svc.byUser(userId);
        return x == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(x);
    }

    @GetMapping
    public List<SellerSummary> list(@RequestAttribute String userRole,
            @RequestParam(required = false) SellerStatus status) {
        ensureAdmin(userRole);
        return svc.list(status);
    }

    @PatchMapping("/{id}/status")
    public SellerSummary status(@PathVariable Long id, @RequestAttribute String userRole,
            @Valid @RequestBody StatusRequest req) {
        ensureAdmin(userRole);
        return svc.setStatus(id, req.status());
    }

    @PostMapping("/me/payouts")
    public SellerSummary payout(@RequestAttribute Long userId, @RequestAttribute String userRole,
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey, @Valid @RequestBody PayoutRequest req) {
        ensureSeller(userRole);
        return svc.requestPayout(userId, req.amount(), idempotencyKey);
    }

    private static void ensureSeller(String role) {
        if (!"SELLER".equalsIgnoreCase(role) && !"ADMIN".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Seller role required");

        }
    }

    private static void ensureAdmin(String role) {
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Admin role required");

        }
    }
}
