package com.catalogix.cart.controller;

import com.catalogix.cart.dto.AddWishlistItemRequest;
import com.catalogix.cart.dto.MoveToCartRequest;
import com.catalogix.cart.dto.WishlistItemResponse;
import com.catalogix.cart.svc.WishlistSvc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Amazon/Flipkart-style "saved for later" list — lives in cart-svc rather
// than its own service since it's a lightweight, self-contained concept
// (userId + productId, no quantity, no checkout involvement) that shares
// exactly the same product/stock lookups CartController already needs.
@RestController
@RequestMapping("/wishlist")
public class WishlistController {

    private final WishlistSvc svc;

    public WishlistController(WishlistSvc svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<WishlistItemResponse> list(HttpServletRequest request) {
        return svc.list(userId(request), bearer(request));
    }

    @PostMapping
    public ResponseEntity<WishlistItemResponse> add(
            @Valid @RequestBody AddWishlistItemRequest req, HttpServletRequest request
    ) {
        return ResponseEntity.status(201).body(svc.add(userId(request), req.getProductId(), bearer(request)));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> remove(@PathVariable Long productId, HttpServletRequest request) {
        svc.remove(userId(request), productId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{productId}/move-to-cart")
    public ResponseEntity<Void> moveToCart(
            @PathVariable Long productId,
            @Valid @RequestBody(required = false) MoveToCartRequest req,
            HttpServletRequest request
    ) {
        int quantity = req != null && req.getQuantity() != null ? req.getQuantity() : 1;
        svc.moveToCart(userId(request), productId, quantity, bearer(request));
        return ResponseEntity.noContent().build();
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }

    private String bearer(HttpServletRequest request) {
        return (String) request.getAttribute("bearerToken");
    }
}
