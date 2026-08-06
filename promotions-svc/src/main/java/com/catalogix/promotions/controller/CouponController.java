package com.catalogix.promotions.controller;

import com.catalogix.promotions.dto.*;
import com.catalogix.promotions.exception.ForbiddenException;
import com.catalogix.promotions.svc.CouponSvc;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class CouponController {

    private final CouponSvc svc;

    public CouponController(CouponSvc svc) {
        this.svc = svc;
    }

    // Called by cart-svc whenever a cart's coupon or contents change — pure
    // preview, no state mutated.
    @PostMapping("/promotions/{code}/preview")
    public DiscountResponse preview(@PathVariable String code, @Valid @RequestBody ApplyCouponRequest req) {
        return svc.preview(code, req.getSubtotal());
    }

    // Called by checkout-svc, and only by checkout-svc, at the moment an
    // order is actually being placed.
    @PostMapping("/promotions/{code}/commit")
    public DiscountResponse commit(@PathVariable String code, @Valid @RequestBody ApplyCouponRequest req) {
        return svc.commit(code, req.getSubtotal());
    }

    // Compensation — called by checkout-svc's outbox processor.
    @PostMapping("/promotions/{code}/release")
    public ResponseEntity<Void> release(@PathVariable String code) {
        svc.release(code);
        return ResponseEntity.noContent().build();
    }

    // ---- Admin-only coupon management (unchanged external contract —
    // still mounted at /coupons so the existing frontend needs no changes) ----

    @PostMapping("/coupons")
    public ResponseEntity<CouponResponse> create(
            @RequestAttribute("userRole") String role,
            @Valid @RequestBody CreateCouponRequest req
    ) {
        requireAdmin(role);
        return ResponseEntity.status(201).body(svc.create(req));
    }

    @GetMapping("/coupons")
    public List<CouponResponse> list(@RequestAttribute("userRole") String role) {
        requireAdmin(role);
        return svc.listAll();
    }

    @PatchMapping("/coupons/{id}/deactivate")
    public ResponseEntity<CouponResponse> deactivate(
            @PathVariable Long id,
            @RequestAttribute("userRole") String role
    ) {
        requireAdmin(role);
        return ResponseEntity.ok(svc.deactivate(id));
    }

    private void requireAdmin(String role) {
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Only admins may manage coupons");
        }
    }
}
