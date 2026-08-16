package com.catalogix.review.svc;

import com.catalogix.review.client.OrderClient;
import com.catalogix.review.dto.PagedResponse;
import com.catalogix.review.dto.RatingSummaryResponse;
import com.catalogix.review.dto.ReviewResponse;
import com.catalogix.review.dto.SubmitReviewRequest;
import com.catalogix.review.exception.ForbiddenException;
import com.catalogix.review.exception.ReviewNotFoundException;
import com.catalogix.review.model.Review;
import com.catalogix.review.repository.ReviewRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class ReviewSvc {

    private final ReviewRepository repo;
    private final OrderClient orderClient;

    public ReviewSvc(ReviewRepository repo, OrderClient orderClient) {
        this.repo = repo;
        this.orderClient = orderClient;
    }

    @Transactional(readOnly = true)
    public PagedResponse<ReviewResponse> listForProduct(Long productId, Pageable pageable) {
        Page<Review> page = repo.findByProductIdOrderByCreatedAtDesc(productId, pageable);
        return PagedResponse.from(page, page.getContent().stream().map(ReviewResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> listMine(Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(ReviewResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RatingSummaryResponse getSummary(Long productId) {
        Object[] row = repo.aggregateForProduct(productId);
        long count = row[1] != null ? ((Number) row[1]).longValue() : 0;
        BigDecimal average = row[0] != null
                ? BigDecimal.valueOf(((Number) row[0]).doubleValue()).setScale(1, RoundingMode.HALF_UP)
                : null;
        return new RatingSummaryResponse(productId, average, count);
    }

    // Create-or-update: see Review's Javadoc for why this doubles as "edit
    // your review" without a separate endpoint.
    @Transactional
    public ReviewResponse submit(Long userId, String email, Long productId, SubmitReviewRequest req, String bearerToken) {
        Review review = repo.findByUserIdAndProductId(userId, productId).orElseGet(Review::new);
        review.setUserId(userId);
        review.setProductId(productId);
        review.setReviewerEmail(email);
        review.setRating(req.getRating());
        review.setTitle(req.getTitle());
        review.setBody(req.getBody());
        // Re-checked on every submit, not just the first — if someone writes
        // a review before delivery and edits it afterward, the badge should
        // catch up, same as the rest of this snapshot-on-write design still
        // reflecting the truth at the moment it was last touched.
        review.setVerifiedPurchase(orderClient.isVerifiedPurchase(productId, bearerToken));
        return ReviewResponse.from(repo.save(review));
    }

    @Transactional
    public void delete(Long reviewId, Long userId, String role) {
        Review review = repo.findById(reviewId).orElseThrow(() -> new ReviewNotFoundException(reviewId));
        boolean isOwner = review.getUserId() != null && review.getUserId().equals(userId);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(role);
        if (!isOwner && !isAdmin) {
            throw new ForbiddenException("Only the review's author or an admin may delete it");
        }
        repo.delete(review);
    }
}
