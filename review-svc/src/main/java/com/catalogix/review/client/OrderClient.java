package com.catalogix.review.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * review-svc's read-only view of checkout-svc's order history — used only
 * to decide whether a submitted review earns the "Verified Purchase" badge.
 * Forwards the caller's own bearer token (checking your own purchase
 * history isn't privileged) rather than minting a system token.
 *
 * Degrades gracefully rather than failing the review submission: if
 * checkout-svc is slow or down, the review still gets saved, just without
 * the badge — a review losing its verified checkmark is a much smaller
 * problem than a user's review vanishing because an unrelated service hiccupped.
 */
@Component
public class OrderClient {

    private final RestTemplate restTemplate;
    private final String checkoutSvcUrl;

    public OrderClient(RestTemplate restTemplate, @Value("${CHECKOUT_SVC_URL}") String checkoutSvcUrl) {
        this.restTemplate = restTemplate;
        this.checkoutSvcUrl = checkoutSvcUrl;
    }

    @CircuitBreaker(name = "checkoutSvc", fallbackMethod = "fallback")
    public boolean isVerifiedPurchase(Long productId, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        var resp = restTemplate.exchange(
                checkoutSvcUrl + "/orders/verified-purchase?productId=" + productId,
                HttpMethod.GET, new HttpEntity<>(headers), RawVerifiedPurchase.class);
        return resp.getBody() != null && resp.getBody().verified;
    }

    @SuppressWarnings("unused")
    private boolean fallback(Long productId, String bearerToken, Throwable t) {
        return false;
    }

    static class RawVerifiedPurchase {
        public boolean verified;
    }
}
