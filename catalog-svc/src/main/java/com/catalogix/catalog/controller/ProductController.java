package com.catalogix.catalog.controller;

import com.catalogix.catalog.dto.CreateProductRequest;
import com.catalogix.catalog.dto.PagedResponse;
import com.catalogix.catalog.dto.ProductResponse;
import com.catalogix.catalog.dto.ProductSortOption;
import com.catalogix.catalog.dto.StockAdjustmentRequest;
import com.catalogix.catalog.svc.ProductSvc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductSvc svc;

    public ProductController(ProductSvc svc) {
        this.svc = svc;
    }

    // Amazon/Flipkart-style listing: free-text search, category and price
    // filters, and a friendly sortBy — all optional and combinable, e.g.
    // GET /products?search=phone&category=ELECTRONICS&minPrice=100&maxPrice=500&sortBy=PRICE_LOW_TO_HIGH
    @GetMapping
    public ResponseEntity<PagedResponse<ProductResponse>> listAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) ProductSortOption sortBy,
            @PageableDefault(size = 20, sort = "id") Pageable pageable,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(svc.search(search, category, minPrice, maxPrice, sortBy, pageable, bearer(request)));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody CreateProductRequest req,
            HttpServletRequest request
    ) {
        ProductResponse created = svc.create(req, userId, bearer(request));

        URI location = ServletUriComponentsBuilder
            .fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.getId())
            .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getOne(@PathVariable long id, HttpServletRequest request) {
        return svc.findById(id, bearer(request)).map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

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

    // Adjust stock (positive = restock, negative = reserve/sell) — forwards
    // to inventory-svc. Used by the admin UI for manual corrections.
    // checkout-svc, on the actual purchase path, calls inventory-svc
    // directly rather than through here (see checkout-svc's InventoryClient).
    @PatchMapping("/{id}/stock")
    public ResponseEntity<ProductResponse> adjustStock(
            @PathVariable long id,
            @Valid @RequestBody StockAdjustmentRequest req,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userRole") String role,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(svc.adjustStock(id, req.getDelta(), userId, role, bearer(request)));
    }

    private String bearer(HttpServletRequest request) {
        return (String) request.getAttribute("bearerToken");
    }
}
