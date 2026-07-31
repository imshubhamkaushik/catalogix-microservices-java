package com.catalogix.product.controller;

import com.catalogix.product.dto.CreateProductRequest;
import com.catalogix.product.dto.PagedResponse;
import com.catalogix.product.dto.ProductResponse;
import com.catalogix.product.dto.StockAdjustmentRequest;
import com.catalogix.product.svc.ProductSvc;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

// Caller identity comes from JwtAuthFilter, which verifies the bearer token and
// attaches userId/userRole as request attributes — no more trusting a client header.

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductSvc svc;

    public ProductController(ProductSvc svc) {
        this.svc = svc;
    }

    // Paginated, searchable, filterable product listing.
    // GET /products?search=phone&category=electronics&page=0&size=20&sort=price,asc
    @GetMapping
    public ResponseEntity<PagedResponse<ProductResponse>> listAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @PageableDefault(size = 20, sort = "id") Pageable pageable
    ) {
        return ResponseEntity.ok(svc.search(search, category, pageable));
    }

    // Create product with validation. Ownership is assigned from the caller's JWT.
    @PostMapping
    public ResponseEntity<ProductResponse> create(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody CreateProductRequest req
    ) {
        ProductResponse created = svc.create(req, userId);

        URI location = ServletUriComponentsBuilder
            .fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.getId())
            .toUri();

        return ResponseEntity.created(location).body(created);
    }

    // Get single product
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getOne(@PathVariable long id) {
        return svc.findById(id).map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // Delete product — owner or admin only.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable long id,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userRole") String role
    ) {
        if (!svc.deleteById(id, userId, role)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    // Adjust stock (positive = restock, negative = reserve/sell). Called both from the
    // admin UI and, internally, by order-svc (with the placing user's token forwarded)
    // when an order is created or cancelled.
    @PatchMapping("/{id}/stock")
    public ResponseEntity<ProductResponse> adjustStock(
            @PathVariable long id,
            @Valid @RequestBody StockAdjustmentRequest req
    ) {
        return ResponseEntity.ok(svc.adjustStock(id, req.getDelta()));
    }
}
