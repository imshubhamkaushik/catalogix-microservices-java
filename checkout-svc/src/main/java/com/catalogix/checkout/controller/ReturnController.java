package com.catalogix.checkout.controller;

import com.catalogix.checkout.dto.PagedResponse;
import com.catalogix.checkout.dto.RejectReturnRequest;
import com.catalogix.checkout.dto.RequestReturnRequest;
import com.catalogix.checkout.dto.ReturnResponse;
import com.catalogix.checkout.exception.ForbiddenException;
import com.catalogix.checkout.model.ReturnStatus;
import com.catalogix.checkout.svc.ReturnSvc;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Amazon/Flipkart-style returns: request against a delivered order (see
 * ReturnSvc for the 7-day window + already-returned-quantity checks), an
 * admin approves (triggers refund + best-effort restock) or rejects.
 *
 * Split across two path prefixes deliberately: creation is nested under
 * /orders/{orderId}/returns since a return only ever makes sense in the
 * context of a specific order, while everything else (list mine, get one,
 * admin review) lives under the flatter /returns — same split OrderController
 * itself uses between /orders/checkout and /orders/{id}.
 */
@RestController
public class ReturnController {

    private final ReturnSvc svc;

    public ReturnController(ReturnSvc svc) {
        this.svc = svc;
    }

    @PostMapping("/orders/{orderId}/returns")
    public ResponseEntity<ReturnResponse> requestReturn(
            @PathVariable Long orderId,
            @Valid @RequestBody RequestReturnRequest req,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userRole") String role
    ) {
        return ResponseEntity.status(201).body(svc.requestReturn(orderId, userId, role, req));
    }

    @GetMapping("/returns/mine")
    public List<ReturnResponse> listMine(@RequestAttribute("userId") Long userId) {
        return svc.listMine(userId);
    }

    @GetMapping("/returns/{id}")
    public ReturnResponse getOne(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userRole") String role
    ) {
        return svc.getOne(id, userId, role);
    }

    // Admin review queue — defaults to showing only pending requests
    // (?status=REQUESTED is the common case), but any status can be
    // requested to see the full history.
    @GetMapping("/returns")
    public PagedResponse<ReturnResponse> listAll(
            @RequestAttribute("userRole") String role,
            @RequestParam(required = false) ReturnStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        requireAdmin(role);
        return svc.listAll(status, pageable);
    }

    @PostMapping("/returns/{id}/approve")
    public ReturnResponse approve(@PathVariable Long id, @RequestAttribute("userRole") String role) {
        requireAdmin(role);
        return svc.approve(id);
    }

    @PostMapping("/returns/{id}/reject")
    public ReturnResponse reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectReturnRequest req,
            @RequestAttribute("userRole") String role
    ) {
        requireAdmin(role);
        return svc.reject(id, req);
    }

    private void requireAdmin(String role) {
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Admin access required");
        }
    }
}
