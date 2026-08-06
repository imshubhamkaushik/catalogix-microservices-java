package com.catalogix.checkout.client;

import com.catalogix.checkout.exception.ProductUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * checkout-svc's fast path for stock: calls inventory-svc directly rather
 * than routing through catalog-svc, since checkout is the one caller that
 * actually needs the row-locked reserve/release semantics, not a cached or
 * composed read. delta negative = reserve, positive = release.
 */
@Component
public class InventoryClient {

    private final RestTemplate restTemplate;
    private final String inventorySvcUrl;

    public InventoryClient(RestTemplate restTemplate, @Value("${INVENTORY_SVC_URL}") String inventorySvcUrl) {
        this.restTemplate = restTemplate;
        this.inventorySvcUrl = inventorySvcUrl;
    }

    @CircuitBreaker(name = "inventorySvc", fallbackMethod = "fallback")
    public void adjust(Long productId, int delta, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = new java.util.HashMap<String, Object>();
        body.put("delta", delta);
        try {
            restTemplate.exchange(inventorySvcUrl + "/inventory/" + productId + "/adjust",
                    HttpMethod.PATCH, new HttpEntity<>(body, headers), Void.class);
        } catch (HttpClientErrorException.Conflict e) {
            throw new ProductUnavailableException(
                    "Insufficient stock for product " + productId);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductUnavailableException("No stock record for product " + productId);
        }
    }

    @SuppressWarnings("unused")
    private void fallback(Long productId, int delta, String bearerToken, Throwable t) {
        throw new ProductUnavailableException(
                "Inventory is temporarily unavailable, try again shortly (product " + productId + ")");
    }
}
