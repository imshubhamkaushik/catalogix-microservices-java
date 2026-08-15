package com.catalogix.inventory.controller;

import com.catalogix.inventory.dto.AdjustStockRequest;
import com.catalogix.inventory.dto.InitStockRequest;
import com.catalogix.inventory.dto.StockResponse;
import com.catalogix.inventory.exception.ForbiddenException;
import com.catalogix.inventory.svc.InventorySvc;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal service — not exposed through the gateway. catalog-svc calls
 * GET/init (to compose product responses and to seed stock on product
 * creation); checkout-svc calls adjust() directly on the fast path (reserve
 * at order time), and again from its compensation outbox processor on the
 * retry path (release on failure).
 *
 * SECURITY FIX: adjust() used to accept any authenticated user's regular
 * token — since this service has no gateway route, that was assumed to be
 * enough protection, but nothing stopped a user who could reach this port
 * directly (trivial in local dev; depends entirely on network policy
 * elsewhere) from mutating any product's stock outside the checkout saga
 * entirely. adjust() now requires a SYSTEM-role token specifically — both
 * catalog-svc and checkout-svc mint one for this call rather than
 * forwarding whatever token the original request happened to carry. GET and
 * init stay open to any authenticated caller: read is harmless, and init
 * only ever seeds a fresh row for a product the caller just created.
 */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventorySvc svc;

    public InventoryController(InventorySvc svc) {
        this.svc = svc;
    }

    @GetMapping("/{productId}")
    public StockResponse get(@PathVariable Long productId) {
        return svc.get(productId);
    }

    @PostMapping
    public ResponseEntity<StockResponse> init(@Valid @RequestBody InitStockRequest req) {
        StockResponse resp = svc.init(req.getProductId(), req.getQuantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PatchMapping("/{productId}/adjust")
    public StockResponse adjust(
            @PathVariable Long productId,
            @RequestBody AdjustStockRequest req,
            @RequestAttribute("userRole") String role
    ) {
        if (!"SYSTEM".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Stock adjustments must go through checkout-svc or catalog-svc, not be called directly");
        }
        return svc.adjust(productId, req.getDelta());
    }
}
