package com.catalogix.inventory.controller;

import com.catalogix.inventory.dto.AdjustStockRequest;
import com.catalogix.inventory.dto.InitStockRequest;
import com.catalogix.inventory.dto.StockResponse;
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
 * retry path (release on failure) using a system token.
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
    public StockResponse adjust(@PathVariable Long productId, @RequestBody AdjustStockRequest req) {
        return svc.adjust(productId, req.getDelta());
    }
}
