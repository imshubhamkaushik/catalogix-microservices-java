package com.catalogix.catalog.client;

import com.catalogix.catalog.exception.InsufficientStockException;
import com.catalogix.catalog.security.JwtService;
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
 * fetchQuantity/init forward the caller's own bearer token (read/init are
 * low-risk). adjust() is different: mutating stock directly used to be
 * reachable by ANY authenticated user via the caller's own forwarded token,
 * which was the mechanism behind a real authorization gap (any user could
 * drain or inflate any other user's stock). adjust() now mints its own
 * short-lived system token instead — inventory-svc's /adjust endpoint only
 * accepts SYSTEM-role tokens, so authorization is enforced once, up front,
 * in ProductSvc#adjustStock (owner-or-admin check) rather than being
 * re-derivable from whatever token happens to be on the request thread.
 */
@Component
public class InventoryClient {

    private final RestTemplate restTemplate;
    private final String inventorySvcUrl;
    private final JwtService jwtService;

    public InventoryClient(RestTemplate restTemplate,
                            @Value("${INVENTORY_SVC_URL}") String inventorySvcUrl,
                            JwtService jwtService) {
        this.restTemplate = restTemplate;
        this.inventorySvcUrl = inventorySvcUrl;
        this.jwtService = jwtService;
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

    public Integer adjust(Long productId, int delta) {
        HttpHeaders headers = authHeaders("Bearer " + jwtService.generateSystemToken());
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
