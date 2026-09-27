package com.catalogix.fulfillment.controller;

import com.catalogix.fulfillment.dto.ShipmentResponse;
import com.catalogix.fulfillment.dto.StatusRequest;
import com.catalogix.fulfillment.service.FulfillmentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.List;

@RestController
@RequestMapping("/fulfillments")
public class FulfillmentController {
    private final FulfillmentService service;

    public FulfillmentController(FulfillmentService service) {
        this.service = service;
    }

    @GetMapping("/mine")
    public List<ShipmentResponse> mine(@RequestAttribute Long userId) {
        return service.forCustomer(userId);
    }

    @GetMapping("/orders/{orderId}")
    public List<ShipmentResponse> order(
            @PathVariable Long orderId,
            @RequestAttribute Long userId,
            @RequestAttribute String userRole) {
        return service.forOrder(orderId, userId, userRole);
    }

    @GetMapping("/all")
    public List<ShipmentResponse> all(@RequestAttribute String userRole) {
        if (!"ADMIN".equalsIgnoreCase(userRole)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Admin role required");
        }
        return service.all();
    }

    @GetMapping("/seller/me")
    public List<ShipmentResponse> seller(
            @RequestAttribute Long userId,
            @RequestAttribute String userRole) {
        if (!"SELLER".equalsIgnoreCase(userRole) && !"ADMIN".equalsIgnoreCase(userRole)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Seller role required");
        }
        return service.forSeller(userId);
    }

    @PatchMapping("/{id}/status")
    public ShipmentResponse status(
            @PathVariable Long id,
            @RequestAttribute Long userId,
            @RequestAttribute String userRole,
            @Valid @RequestBody StatusRequest request) {
        try {
            return service.update(id, userId, userRole, request.status());
        } catch (OptimisticLockingFailureException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Shipment was updated by another request");
        }
    }
}
