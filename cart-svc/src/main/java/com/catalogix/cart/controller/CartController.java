package com.catalogix.cart.controller;

import com.catalogix.cart.dto.*;
import com.catalogix.cart.svc.CartSvc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/cart")
public class CartController {

    private final CartSvc svc;

    public CartController(CartSvc svc) {
        this.svc = svc;
    }

    @GetMapping
    public CartResponse get(HttpServletRequest request) {
        return svc.getOrCreateCart(userId(request), bearer(request));
    }

    @PostMapping("/items")
    public CartResponse addItem(@Valid @RequestBody AddCartItemRequest req, HttpServletRequest request) {
        return svc.addItem(userId(request), req, bearer(request));
    }

    @PatchMapping("/items/{productId}")
    public CartResponse updateItem(@PathVariable Long productId, @Valid @RequestBody UpdateCartItemRequest req,
                                    HttpServletRequest request) {
        return svc.updateItemQuantity(userId(request), productId, req, bearer(request));
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@PathVariable Long productId, HttpServletRequest request) {
        return svc.removeItem(userId(request), productId, bearer(request));
    }

    @PostMapping("/coupon")
    public CartResponse applyCoupon(@RequestBody java.util.Map<String, String> body, HttpServletRequest request) {
        return svc.applyCoupon(userId(request), body.get("code"), bearer(request));
    }

    @DeleteMapping("/coupon")
    public CartResponse removeCoupon(HttpServletRequest request) {
        return svc.removeCoupon(userId(request), bearer(request));
    }

    // Internal — called only by checkout-svc, using the caller's own
    // forwarded token, to fetch the cart's contents at the moment of
    // checkout. Not what the browser calls; the browser's "Checkout" button
    // hits checkout-svc's POST /orders/checkout, which calls this in turn.
    @GetMapping("/handoff")
    public CheckoutHandoff handoff(HttpServletRequest request) {
        return svc.toCheckoutHandoff(userId(request));
    }

    // Internal — called by checkout-svc immediately after an order created
    // from this cart is successfully persisted.
    @PostMapping("/clear")
    public void clear(HttpServletRequest request) {
        svc.clear(userId(request));
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }

    private String bearer(HttpServletRequest request) {
        return (String) request.getAttribute("bearerToken");
    }
}
