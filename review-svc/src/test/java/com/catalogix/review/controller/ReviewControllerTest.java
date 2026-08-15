package com.catalogix.review.controller;

import com.catalogix.review.dto.*;
import com.catalogix.review.exception.ForbiddenException;
import com.catalogix.review.exception.ReviewNotFoundException;
import com.catalogix.review.security.JwtAuthFilter;
import com.catalogix.review.security.RateLimiterFilter;
import com.catalogix.review.svc.ReviewSvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
@WebMvcTest(
        controllers = ReviewController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class ReviewControllerTest {

    private static final String TOKEN = "Bearer token";

    @Autowired private ObjectMapper mapper;
    @Autowired private MockMvc mvc;
    @MockitoBean private ReviewSvc svc;

    private ReviewResponse sampleResponse() {
        ReviewResponse r = new ReviewResponse();
        r.setId(9L);
        r.setProductId(1L);
        r.setUserId(42L);
        r.setReviewerEmail("buyer@example.com");
        r.setRating(5);
        r.setTitle("Great product");
        r.setBody("Works exactly as described.");
        r.setVerifiedPurchase(true);
        r.setCreatedAt(Instant.now());
        return r;
    }

    @Test
    void listForProductReturnsAPagedResponse() throws Exception {
        PagedResponse<ReviewResponse> page = new PagedResponse<>(List.of(sampleResponse()), 0, 20, 1, 1);
        when(svc.listForProduct(eq(1L), any(Pageable.class))).thenReturn(page);

        mvc.perform(get("/reviews/product/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.content[0].verifiedPurchase").value(true));
    }

    @Test
    void getSummaryReturnsTheAggregate() throws Exception {
        when(svc.getSummary(1L)).thenReturn(new RatingSummaryResponse(1L, new BigDecimal("4.5"), 10));

        mvc.perform(get("/reviews/product/1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.5))
                .andExpect(jsonPath("$.reviewCount").value(10));
    }

    @Test
    void listMineReturnsTheCallersReviews() throws Exception {
        when(svc.listMine(42L)).thenReturn(List.of(sampleResponse()));

        mvc.perform(get("/reviews/mine").requestAttr("userId", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(1));
    }

    @Test
    @SuppressWarnings("null")
    void submitReturnsCreated() throws Exception {
        when(svc.submit(eq(42L), eq("buyer@example.com"), eq(1L), any(SubmitReviewRequest.class), eq(TOKEN)))
                .thenReturn(sampleResponse());

        SubmitReviewRequest req = new SubmitReviewRequest();
        req.setRating(5);
        req.setTitle("Great product");
        req.setBody("Works exactly as described.");

        mvc.perform(post("/reviews/product/1")
                .requestAttr("userId", 42L)
                .requestAttr("userEmail", "buyer@example.com")
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(5));
    }

    @Test
    void submitRejectsARatingOutOfRange() throws Exception {
        SubmitReviewRequest req = new SubmitReviewRequest();
        req.setRating(7);

        mvc.perform(post("/reviews/product/1")
                .requestAttr("userId", 42L)
                .requestAttr("userEmail", "buyer@example.com")
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitRejectsAMissingRating() throws Exception {
        mvc.perform(post("/reviews/product/1")
                .requestAttr("userId", 42L)
                .requestAttr("userEmail", "buyer@example.com")
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mvc.perform(delete("/reviews/9").requestAttr("userId", 42L).requestAttr("userRole", "USER"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturnsForbiddenWhenNotOwnerOrAdmin() throws Exception {
        doThrow(new ForbiddenException("Only the review's author or an admin may delete it"))
                .when(svc).delete(9L, 7L, "USER");

        mvc.perform(delete("/reviews/9").requestAttr("userId", 7L).requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteReturnsNotFoundForAnUnknownReview() throws Exception {
        doThrow(new ReviewNotFoundException(99L)).when(svc).delete(99L, 42L, "USER");

        mvc.perform(delete("/reviews/99").requestAttr("userId", 42L).requestAttr("userRole", "USER"))
                .andExpect(status().isNotFound());
    }
}
