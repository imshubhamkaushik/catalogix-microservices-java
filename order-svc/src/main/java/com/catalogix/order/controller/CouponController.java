package com.catalogix.order.controller;

import com.catalogix.order.dto.CouponResponse;
import com.catalogix.order.dto.CreateCouponRequest;
import com.catalogix.order.exception.ForbiddenException;
import com.catalogix.order.svc.CouponSvc;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Admin-only coupon management. Applying a coupon to a cart/order is a
// separate, non-admin flow — see CartController.applyCoupon.
@RestController
@RequestMapping("/coupons")
public class CouponController {

    private final CouponSvc svc;

    public CouponController(CouponSvc svc) {
        this.svc = svc;
    }

    @PostMapping
    public ResponseEntity<CouponResponse> create(
            @RequestAttribute("userRole") String role,
            @Valid @RequestBody CreateCouponRequest req
    ) {
        requireAdmin(role);
        return ResponseEntity.status(201).body(svc.create(req));
    }

    @GetMapping
    public List<CouponResponse> list(@RequestAttribute("userRole") String role) {
        requireAdmin(role);
        return svc.listAll();
    }

    // Deactivates rather than deletes — past orders may still reference this
    // code (Order.appliedCouponCode) and shouldn't be left dangling.
    @PatchMapping("/{id}/deactivate")
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
