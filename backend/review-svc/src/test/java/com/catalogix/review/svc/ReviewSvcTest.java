package com.catalogix.review.svc;

import com.catalogix.review.client.OrderClient;
import com.catalogix.review.dto.RatingSummaryResponse;
import com.catalogix.review.dto.ReviewResponse;
import com.catalogix.review.dto.SubmitReviewRequest;
import com.catalogix.review.exception.ForbiddenException;
import com.catalogix.review.exception.ReviewNotFoundException;
import com.catalogix.review.model.Review;
import com.catalogix.review.repository.ReviewRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReviewSvcTest {

    @Mock private ReviewRepository repo;
    @Mock private OrderClient orderClient;

    private ReviewSvc svc;

    private static final String TOKEN = "Bearer test-token";
    private static final Long USER_ID = 42L;
    private static final String EMAIL = "buyer@example.com";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new ReviewSvc(repo, orderClient);
    }

    private SubmitReviewRequest request(int rating) {
        SubmitReviewRequest req = new SubmitReviewRequest();
        req.setRating(rating);
        req.setTitle("Great product");
        req.setBody("Works exactly as described.");
        return req;
    }

    // ---- submit: create ----

    @Test
    void submitCreatesANewVerifiedReviewWhenThereIsADeliveredOrder() {
        when(repo.findByUserIdAndProductId(USER_ID, 1L)).thenReturn(Optional.empty());
        when(orderClient.isVerifiedPurchase(1L, TOKEN)).thenReturn(true);
        when(repo.save(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            r.setId(9L);
            return r;
        });

        ReviewResponse resp = svc.submit(USER_ID, EMAIL, 1L, request(5), TOKEN);

        assertTrue(resp.isVerifiedPurchase());
        assertEquals(EMAIL, resp.getReviewerEmail());
        assertEquals(5, resp.getRating());
    }

    @Test
    void submitCreatesAnUnverifiedReviewWhenThereIsNoDeliveredOrder() {
        when(repo.findByUserIdAndProductId(USER_ID, 1L)).thenReturn(Optional.empty());
        when(orderClient.isVerifiedPurchase(1L, TOKEN)).thenReturn(false);
        when(repo.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewResponse resp = svc.submit(USER_ID, EMAIL, 1L, request(3), TOKEN);

        assertFalse(resp.isVerifiedPurchase());
    }

    // ---- submit: update (create-or-update semantics) ----

    @Test
    void submitUpdatesAnExistingReviewInsteadOfCreatingADuplicate() {
        Review existing = new Review();
        existing.setId(9L);
        existing.setUserId(USER_ID);
        existing.setProductId(1L);
        existing.setRating(2);
        existing.setTitle("Meh");
        when(repo.findByUserIdAndProductId(USER_ID, 1L)).thenReturn(Optional.of(existing));
        when(orderClient.isVerifiedPurchase(1L, TOKEN)).thenReturn(true);
        when(repo.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewResponse resp = svc.submit(USER_ID, EMAIL, 1L, request(5), TOKEN);

        assertEquals(9L, resp.getId()); // same row, not a new one
        assertEquals(5, resp.getRating());
        assertEquals("Great product", resp.getTitle());
        verify(repo, times(1)).save(any(Review.class));
    }

    @Test
    void submitRechecksVerifiedPurchaseOnEveryUpdate() {
        Review existing = new Review();
        existing.setId(9L);
        existing.setUserId(USER_ID);
        existing.setProductId(1L);
        existing.setVerifiedPurchase(false); // wasn't verified when first written
        when(repo.findByUserIdAndProductId(USER_ID, 1L)).thenReturn(Optional.of(existing));
        when(orderClient.isVerifiedPurchase(1L, TOKEN)).thenReturn(true); // now it's delivered
        when(repo.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewResponse resp = svc.submit(USER_ID, EMAIL, 1L, request(4), TOKEN);

        assertTrue(resp.isVerifiedPurchase());
    }

    // ---- delete ----

    @Test
    void deleteThrowsWhenReviewDoesNotExist() {
        when(repo.findById(9L)).thenReturn(Optional.empty());

        assertThrows(ReviewNotFoundException.class, () -> svc.delete(9L, USER_ID, "USER"));
    }

    @Test
    void deleteRejectsNonOwnerNonAdmin() {
        Review review = new Review();
        review.setId(9L);
        review.setUserId(USER_ID);
        when(repo.findById(9L)).thenReturn(Optional.of(review));

        assertThrows(ForbiddenException.class, () -> svc.delete(9L, 7L, "USER"));
        verify(repo, never()).delete(any());
    }

    @Test
    void deleteAllowsTheOwner() {
        Review review = new Review();
        review.setId(9L);
        review.setUserId(USER_ID);
        when(repo.findById(9L)).thenReturn(Optional.of(review));

        svc.delete(9L, USER_ID, "USER");

        verify(repo).delete(review);
    }

    @Test
    void deleteAllowsAnAdminEvenWhenNotTheOwner() {
        Review review = new Review();
        review.setId(9L);
        review.setUserId(USER_ID);
        when(repo.findById(9L)).thenReturn(Optional.of(review));

        svc.delete(9L, 999L, "ADMIN");

        verify(repo).delete(review);
    }

    // ---- listMine ----

    @Test
    void listMineReturnsOnlyTheCallersReviews() {
        Review review = new Review();
        review.setId(9L);
        review.setUserId(USER_ID);
        review.setProductId(1L);
        when(repo.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(review));

        List<ReviewResponse> result = svc.listMine(USER_ID);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getProductId());
    }

    // ---- getSummary ----

    @Test
    void getSummaryReturnsNullAverageWhenThereAreNoReviews() {
        when(repo.aggregateForProduct(1L)).thenReturn(new Object[]{null, 0L});

        RatingSummaryResponse summary = svc.getSummary(1L);

        assertNull(summary.averageRating());
        assertEquals(0, summary.reviewCount());
    }

    @Test
    void getSummaryRoundsTheAverageToOneDecimalPlace() {
        when(repo.aggregateForProduct(1L)).thenReturn(new Object[]{4.666666, 3L});

        RatingSummaryResponse summary = svc.getSummary(1L);

        assertEquals(new BigDecimal("4.7"), summary.averageRating());
        assertEquals(3, summary.reviewCount());
    }
}
