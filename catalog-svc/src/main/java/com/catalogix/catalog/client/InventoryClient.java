package com.catalogix.catalog.client;

import com.catalogix.catalog.exception.InsufficientStockException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * catalog-svc's own view of inventory-svc — used only to compose stock into
 * ProductResponse for the browser-facing GET/PATCH-stock endpoints, which
 * keep their existing external contract so the frontend needed zero changes
 * for this split. checkout-svc, notably, does NOT go through this service
 * or this client for stock reservation — it calls inventory-svc directly.
 *
 * Every call forwards the caller's own bearer token: inventory-svc's
 * JwtAuthFilter requires one on every request, and this service has no
 * system-token-minting capability of its own (only checkout-svc's
 * background outbox processor does) — nor should it need one, since every
 * call here happens on a request thread that already has a real user's token.
 */
@Component
public class InventoryClient {

    private final RestTemplate restTemplate;
    private final String inventorySvcUrl;

    public InventoryClient(RestTemplate restTemplate,
                            @Value("${INVENTORY_SVC_URL}") String inventorySvcUrl) {
        this.restTemplate = restTemplate;
        this.inventorySvcUrl = inventorySvcUrl;
    }

    @CircuitBreaker(name = "inventorySvc", fallbackMethod = "fetchFallback")
    public Integer fetchQuantity(Long productId, String bearerToken) {
        HttpHeaders headers = authHeaders(bearerToken);
        var resp = restTemplate.exchange(inventorySvcUrl + "/inventory/" + productId,
                HttpMethod.GET, new HttpEntity<>(headers), StockDto.class);
        return resp.getBody() != null ? resp.getBody().quantity : null;
    }

    @SuppressWarnings("unused")
    private Integer fetchFallback(Long productId, String bearerToken, Throwable t) {
        return null; // stock shown as "unknown" rather than failing the whole product read
    }

    public void init(Long productId, int initialQuantity, String bearerToken) {
        HttpHeaders headers = authHeaders(bearerToken);
        var body = new java.util.HashMap<String, Object>();
        body.put("productId", productId);
        body.put("quantity", initialQuantity);
        restTemplate.exchange(inventorySvcUrl + "/inventory", HttpMethod.POST,
                new HttpEntity<>(body, headers), StockDto.class);
    }

    public Integer adjust(Long productId, int delta, String bearerToken) {
        HttpHeaders headers = authHeaders(bearerToken);
        var body = new java.util.HashMap<String, Object>();
        body.put("delta", delta);
        try {
            var resp = restTemplate.exchange(
                    inventorySvcUrl + "/inventory/" + productId + "/adjust",
                    HttpMethod.PATCH, new HttpEntity<>(body, headers), StockDto.class);
            return resp.getBody() != null ? resp.getBody().quantity : null;
        } catch (HttpClientErrorException.Conflict e) {
            throw new InsufficientStockException(productId, -1, -delta);
        }
    }

    private HttpHeaders authHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    static class StockDto {
        public Long productId;
        public Integer quantity;
    }
}
