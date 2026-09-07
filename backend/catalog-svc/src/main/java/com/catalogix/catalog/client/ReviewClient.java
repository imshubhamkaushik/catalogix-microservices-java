package com.catalogix.catalog.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

/**
 * catalog-svc's own view of review-svc — used only to compose an average
 * rating and review count into ProductResponse, the same "live composition
 * on read" pattern InventoryClient#fetchQuantity already uses for stock, not
 * a new architectural style introduced just for reviews.
 *
 * Forwards the caller's own bearer token: a product's aggregate rating
 * isn't privileged information, review-svc's summary endpoint doesn't
 * require anything more than "authenticated," so there's no reason to mint
 * a system token here.
 */
@Component
public class ReviewClient {

    private final RestTemplate restTemplate;
    private final String reviewSvcUrl;

    public ReviewClient(RestTemplate restTemplate, @Value("${REVIEW_SVC_URL}") String reviewSvcUrl) {
        this.restTemplate = restTemplate;
        this.reviewSvcUrl = reviewSvcUrl;
    }

    @CircuitBreaker(name = "reviewSvc", fallbackMethod = "fallback")
    public Summary fetchSummary(Long productId, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        var resp = restTemplate.exchange(
                reviewSvcUrl + "/reviews/product/" + productId + "/summary",
                HttpMethod.GET, new HttpEntity<>(headers), RawSummary.class);
        RawSummary body = resp.getBody();
        return body != null ? new Summary(body.averageRating, body.reviewCount) : new Summary(null, 0);
    }

    @SuppressWarnings("unused")
    private Summary fallback(Long productId, String bearerToken, Throwable t) {
        // Rating shown as "no data" rather than failing the whole product
        // read — same reasoning as InventoryClient's stock fallback.
        return new Summary(null, 0);
    }

    public record Summary(BigDecimal averageRating, long reviewCount) {}

    static class RawSummary {
        public Long productId;
        public BigDecimal averageRating;
        public long reviewCount;
    }
}
