package com.catalogix.checkout.client;

import com.catalogix.checkout.exception.ProductUnavailableException;
import com.catalogix.checkout.security.JwtService;
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
 *
 * SECURITY FIX: this used to forward the end user's own bearer token on
 * every call — meaning inventory-svc's /adjust endpoint had to accept
 * regular user tokens, which is exactly what let any authenticated user
 * bypass the checkout saga entirely and hit inventory-svc directly. Every
 * call here now mints its own short-lived system token instead (same
 * mechanism CompensationOutboxProcessor already used for its retry path);
 * inventory-svc's /adjust endpoint now rejects anything that isn't a
 * SYSTEM-role token. Authorization for "should this reservation happen at
 * all" is checkout-svc's own job (the saga itself, the order's ownership
 * checks) — inventory-svc no longer needs to re-derive it from a forwarded
 * user identity it was never really in a position to validate anyway.
 */
@Component
public class InventoryClient {

    private final RestTemplate restTemplate;
    private final String inventorySvcUrl;
    private final JwtService jwtService;

    public InventoryClient(RestTemplate restTemplate, @Value("${INVENTORY_SVC_URL}") String inventorySvcUrl,
                            JwtService jwtService) {
        this.restTemplate = restTemplate;
        this.inventorySvcUrl = inventorySvcUrl;
        this.jwtService = jwtService;
    }

    @CircuitBreaker(name = "inventorySvc", fallbackMethod = "fallback")
    public void adjust(Long productId, int delta) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateSystemToken());
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
    private void fallback(Long productId, int delta, Throwable t) {
        throw new ProductUnavailableException(
                "Inventory is temporarily unavailable, try again shortly (product " + productId + ")");
    }
}
