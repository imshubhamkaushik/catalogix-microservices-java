package com.catalogix.order.controller;

import com.catalogix.order.dto.CreateOrderRequest;
import com.catalogix.order.dto.OrderResponse;
import com.catalogix.order.dto.PagedResponse;
import com.catalogix.order.dto.PayOrderRequest;
import com.catalogix.order.dto.UpdateOrderStatusRequest;
import com.catalogix.order.exception.ForbiddenException;
import com.catalogix.order.svc.OrderSvc;

import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Map;

// Caller identity (userId/userRole) and the raw bearer token come from JwtAuthFilter's
// request attributes. The token is forwarded to product-svc by OrderSvc so the whole
// call chain is authorized as the same original user.

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderSvc svc;

    public OrderController(OrderSvc svc) {
        this.svc = svc;
    }

    // Idempotency-Key is optional: pass a client-generated UUID (e.g. per "place
    // order" button click) and a retried request with the same key returns the
    // original order — 200, not 201 — instead of creating a duplicate.
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("bearerToken") String bearerToken,
            @RequestAttribute("userEmail") String userEmail,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest req
    ) {
        OrderSvc.OrderCreationResult result;
        try {
            result = svc.createOrder(userId, req, bearerToken, idempotencyKey, userEmail);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent requests raced with the same Idempotency-Key; the
            // other one won — return its order instead of erroring, which is
            // the whole point of idempotency.
            OrderResponse existing = svc.findExistingByIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> e);
            result = new OrderSvc.OrderCreationResult(existing, false);
        }

        if (!result.wasNew()) {
            return ResponseEntity.ok(result.order());
        }

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.order().getId())
                .toUri();

        return ResponseEntity.created(location).body(result.order());
    }

    // Regular users see only their own orders; admins see everyone's.
    @GetMapping
    public ResponseEntity<PagedResponse<OrderResponse>> list(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userRole") String role,
            @PageableDefault(size = 20, sort = "id") Pageable pageable
    ) {
        return ResponseEntity.ok(svc.listOrders(userId, role, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOne(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userRole") String role
    ) {
        return ResponseEntity.ok(svc.getOrder(id, userId, role));
    }

    // Processes a mock payment for an order still awaiting one. Always 200 —
    // even a declined payment is a normal outcome, not a server error; check
    // the returned payment.status and order.status to see what happened.
    @PostMapping("/{id}/pay")
    public ResponseEntity<Map<String, Object>> pay(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userRole") String role,
            @RequestAttribute("bearerToken") String bearerToken,
            @RequestAttribute("userEmail") String userEmail,
            @Valid @RequestBody PayOrderRequest req
    ) {
        OrderSvc.OrderPaymentResult result = svc.payOrder(id, userId, role, req, bearerToken, userEmail);
        return ResponseEntity.ok(Map.of("order", result.order(), "payment", result.payment()));
    }

    // Admin-only: advances CONFIRMED -> SHIPPED -> DELIVERED. Not exposed to
    // regular users — there's no seller-fulfillment flow yet (see roadmap).
    @PatchMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable Long id,
            @RequestAttribute("userRole") String role,
            @Valid @RequestBody UpdateOrderStatusRequest req
    ) {
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Only admins may update order status");
        }
        return ResponseEntity.ok(svc.updateStatus(id, req.getStatus()));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancel(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userRole") String role,
            @RequestAttribute("bearerToken") String bearerToken,
            @RequestAttribute("userEmail") String userEmail
    ) {
        return ResponseEntity.ok(svc.cancelOrder(id, userId, role, bearerToken, userEmail));
    }
}
