package com.catalogix.checkout.controller;

import com.catalogix.checkout.dto.CreateOrderRequest;
import com.catalogix.checkout.dto.OrderResponse;
import com.catalogix.checkout.dto.PagedResponse;
import com.catalogix.checkout.dto.PayOrderRequest;
import com.catalogix.checkout.dto.UpdateOrderStatusRequest;
import com.catalogix.checkout.exception.ForbiddenException;
import com.catalogix.checkout.svc.CheckoutSvc;

import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;

// Caller identity (userId/userRole) and the raw bearer token come from
// JwtAuthFilter's request attributes. The token is forwarded to every
// downstream service CheckoutSvc calls, so the whole chain is authorized
// as the same original user.

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final CheckoutSvc svc;

    public OrderController(CheckoutSvc svc) {
        this.svc = svc;
    }

    // Direct API: caller supplies the item list explicitly (no cart
    // involved) — kept for API consumers that aren't the shopping-cart UI.
    // Idempotency-Key is optional: pass a client-generated UUID and a
    // retried request with the same key returns the original order — 200,
    // not 201 — instead of creating a duplicate.
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("bearerToken") String bearerToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest req
    ) {
        CheckoutSvc.OrderCreationResult result = withIdempotencyRaceRecovery(
                userId, idempotencyKey,
                () -> svc.createOrder(userId, req, bearerToken, idempotencyKey));
        return respond(result);
    }

    // Cart-driven checkout — what the frontend's "Checkout" button calls.
    // Pulls the current cart from cart-svc, places the order the same way
    // create() does, then clears the cart. Has the SAME idempotency-key
    // race recovery as create() above — the original design was missing
    // this on the cart-checkout path specifically, so a concurrent
    // double-click could surface a raw 500 instead of the existing order.
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("bearerToken") String bearerToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        CheckoutSvc.OrderCreationResult result = withIdempotencyRaceRecovery(
                userId, idempotencyKey,
                () -> svc.checkoutFromCart(userId, bearerToken, idempotencyKey));
        return respond(result);
    }

    private CheckoutSvc.OrderCreationResult withIdempotencyRaceRecovery(
            Long userId, String idempotencyKey, Supplier<CheckoutSvc.OrderCreationResult> action
    ) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            // Two concurrent requests raced with the same Idempotency-Key;
            // the other one won — return its order instead of erroring,
            // which is the whole point of idempotency.
            OrderResponse existing = svc.findExistingByIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> e);
            return new CheckoutSvc.OrderCreationResult(existing, false);
        }
    }

    private ResponseEntity<OrderResponse> respond(CheckoutSvc.OrderCreationResult result) {
        if (!result.wasNew()) {
            return ResponseEntity.ok(result.order());
        }
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .replacePath("/orders/{id}")
                .buildAndExpand(result.order().getId())
                .toUri();
        return ResponseEntity.created(location).body(result.order());
    }

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

    @PostMapping("/{id}/pay")
    public ResponseEntity<Map<String, Object>> pay(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userRole") String role,
            @RequestAttribute("bearerToken") String bearerToken,
            @RequestAttribute("userEmail") String userEmail,
            @Valid @RequestBody PayOrderRequest req
    ) {
        CheckoutSvc.OrderPaymentResult result = svc.payOrder(id, userId, role, req, bearerToken, userEmail);
        // Reconstructs the same {order, payment: {status: ...}} shape the
        // frontend already expects (Orders.jsx reads result.payment.status)
        // — this split changed where payment processing happens, not the
        // contract the browser was built against.
        Map<String, Object> payment = Map.of("status", result.paymentSucceeded() ? "SUCCEEDED" : "FAILED");
        return ResponseEntity.ok(Map.of("order", result.order(), "payment", payment));
    }

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
