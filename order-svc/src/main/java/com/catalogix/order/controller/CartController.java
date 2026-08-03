package com.catalogix.order.controller;

import com.catalogix.order.dto.*;
import com.catalogix.order.svc.CartSvc;
import com.catalogix.order.svc.OrderSvc;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * The persistent, server-side cart. Checkout (POST /cart/checkout) hands the
 * cart's contents to OrderSvc.createOrder — all the actual stock
 * reservation/pricing/coupon logic lives there, this just orchestrates
 * "build the request from the cart, place it, clear the cart on success."
 */
@RestController
@RequestMapping("/cart")
public class CartController {

    private final CartSvc cartSvc;
    private final OrderSvc orderSvc;

    public CartController(CartSvc cartSvc, OrderSvc orderSvc) {
        this.cartSvc = cartSvc;
        this.orderSvc = orderSvc;
    }

    @GetMapping
    public ResponseEntity<CartResponse> getCart(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("bearerToken") String bearerToken
    ) {
        return ResponseEntity.ok(cartSvc.getOrCreateCart(userId, bearerToken));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("bearerToken") String bearerToken,
            @Valid @RequestBody AddCartItemRequest req
    ) {
        return ResponseEntity.ok(cartSvc.addItem(userId, req, bearerToken));
    }

    @PatchMapping("/items/{productId}")
    public ResponseEntity<CartResponse> updateItem(
            @PathVariable Long productId,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("bearerToken") String bearerToken,
            @Valid @RequestBody UpdateCartItemRequest req
    ) {
        return ResponseEntity.ok(cartSvc.updateItemQuantity(userId, productId, req, bearerToken));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<CartResponse> removeItem(
            @PathVariable Long productId,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("bearerToken") String bearerToken
    ) {
        return ResponseEntity.ok(cartSvc.removeItem(userId, productId, bearerToken));
    }

    @PostMapping("/coupon")
    public ResponseEntity<CartResponse> applyCoupon(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("bearerToken") String bearerToken,
            @Valid @RequestBody ApplyCouponRequest req
    ) {
        return ResponseEntity.ok(cartSvc.applyCoupon(userId, req, bearerToken));
    }

    @DeleteMapping("/coupon")
    public ResponseEntity<CartResponse> removeCoupon(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("bearerToken") String bearerToken
    ) {
        return ResponseEntity.ok(cartSvc.removeCoupon(userId, bearerToken));
    }

    // Converts the cart into an order (PENDING_PAYMENT, stock reserved) and
    // clears the cart on success. The order itself still needs POST
    // /orders/{id}/pay to actually complete — see OrderController.
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("bearerToken") String bearerToken,
            @RequestAttribute("userEmail") String userEmail,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        CreateOrderRequest orderReq = cartSvc.toOrderRequest(userId);
        OrderSvc.OrderCreationResult result =
                orderSvc.createOrder(userId, orderReq, bearerToken, idempotencyKey, userEmail);

        if (result.wasNew()) {
            cartSvc.clear(userId);
        }

        URI location = org.springframework.web.servlet.support.ServletUriComponentsBuilder
                .fromCurrentContextPath()
                .path("/orders/{id}")
                .buildAndExpand(result.order().getId())
                .toUri();

        return result.wasNew()
                ? ResponseEntity.created(location).body(result.order())
                : ResponseEntity.ok(result.order());
    }
}
