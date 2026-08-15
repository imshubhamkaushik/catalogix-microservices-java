package com.catalogix.user.controller;

import com.catalogix.user.dto.AddressRequest;
import com.catalogix.user.dto.AddressResponse;
import com.catalogix.user.svc.AddressSvc;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Address book for the current authenticated user only — there is no
// admin/other-user variant of these endpoints (unlike UserController's
// getAll/delete), since an address book is inherently a self-service,
// never a directory, concept. Nested under /users so it rides the gateway's
// existing "/users" route with no nginx changes needed.
//
// checkout-svc calls GET /{id} at order-placement time (forwarding the same
// user's own bearer token — reading your own address isn't privileged) to
// snapshot the chosen address onto the order; see PlaceOrderRequest.
@RestController
@RequestMapping("/users/me/addresses")
public class AddressController {

    private final AddressSvc svc;

    public AddressController(AddressSvc svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<AddressResponse> list(@RequestAttribute("userId") Long userId) {
        return svc.listForUser(userId);
    }

    @GetMapping("/{id}")
    public AddressResponse getOne(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        return svc.getOne(userId, id);
    }

    @PostMapping
    public ResponseEntity<AddressResponse> create(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody AddressRequest req
    ) {
        return ResponseEntity.status(201).body(svc.create(userId, req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AddressResponse> update(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody AddressRequest req
    ) {
        return ResponseEntity.ok(svc.update(userId, id, req));
    }

    @PatchMapping("/{id}/default")
    public ResponseEntity<AddressResponse> setDefault(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(svc.setDefault(userId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        svc.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
