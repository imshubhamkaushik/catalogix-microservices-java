package com.catalogix.review.controller;

import com.catalogix.review.dto.PagedResponse;
import com.catalogix.review.dto.RatingSummaryResponse;
import com.catalogix.review.dto.ReviewResponse;
import com.catalogix.review.dto.SubmitReviewRequest;
import com.catalogix.review.svc.ReviewSvc;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/reviews")
public class ReviewController {

    private final ReviewSvc svc;

    public ReviewController(ReviewSvc svc) {
        this.svc = svc;
    }

    @GetMapping("/product/{productId}")
    public PagedResponse<ReviewResponse> listForProduct(
            @PathVariable Long productId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        return svc.listForProduct(productId, pageable);
    }

    // Consumed by catalog-svc's ReviewClient to show a star rating on
    // product listings without pulling every review's full text.
    @GetMapping("/product/{productId}/summary")
    public RatingSummaryResponse getSummary(@PathVariable Long productId) {
        return svc.getSummary(productId);
    }

    @GetMapping("/mine")
    public List<ReviewResponse> listMine(@RequestAttribute("userId") Long userId) {
        return svc.listMine(userId);
    }

    // Create-or-update — see ReviewSvc#submit's Javadoc. Idempotent enough
    // that resubmitting with the same body is a harmless no-op change.
    @PostMapping("/product/{productId}")
    public ResponseEntity<ReviewResponse> submit(
            @PathVariable Long productId,
            @Valid @RequestBody SubmitReviewRequest req,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userEmail") String userEmail,
            @RequestAttribute("bearerToken") String bearerToken
    ) {
        return ResponseEntity.status(201).body(svc.submit(userId, userEmail, productId, req, bearerToken));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("userRole") String role
    ) {
        svc.delete(id, userId, role);
        return ResponseEntity.noContent().build();
    }
}
