package com.catalogix.order.client;

import com.catalogix.order.dto.ProductLookupResponse;
import com.catalogix.order.exception.ProductUnavailableException;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * All of order-svc's outbound HTTP calls to product-svc, guarded by a
 * circuit breaker (config: application.properties, instance name "productSvc").
 *
 * Deliberately a separate bean from OrderSvc: Resilience4j's @CircuitBreaker
 * (like Spring's @Transactional) is AOP-proxy-based, so it only takes effect
 * on calls that arrive from a *different* bean — a method calling its own
 * @CircuitBreaker-annotated sibling method within the same class would bypass
 * the proxy entirely and silently do nothing.
 *
 * ProductUnavailableException (product not found / insufficient stock) is
 * configured as an "ignored" exception for this circuit breaker — those are
 * normal business outcomes, not infrastructure failures, and shouldn't trip
 * the breaker just because customers keep ordering out-of-stock items. Only
 * genuine connectivity failures (timeouts, connection refused, 5xx) count
 * toward the failure rate and trigger the fallback methods below.
 */
@Component
public class ProductSvcClient {

    private final RestTemplate restTemplate;
    private final String productSvcUrl;

    public ProductSvcClient(RestTemplate restTemplate, @Value("${PRODUCT_SVC_URL}") String productSvcUrl) {
        this.restTemplate = restTemplate;
        this.productSvcUrl = productSvcUrl;
    }

    @CircuitBreaker(name = "productSvc", fallbackMethod = "fetchProductFallback")
    public ProductLookupResponse fetchProduct(Long productId, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<ProductLookupResponse> resp = restTemplate.exchange(
                    productSvcUrl + "/products/{id}", HttpMethod.valueOf("GET"), entity,
                    ProductLookupResponse.class, productId);
            ProductLookupResponse body = resp.getBody();
            if (body == null) {
                throw new ProductUnavailableException("Product not found: " + productId);
            }
            return body;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductUnavailableException("Product not found: " + productId);
        }
        // Any other RestClientException (timeout, connection refused, 5xx) propagates
        // unwrapped from here, so the circuit breaker records it as a real failure.
    }

    @SuppressWarnings("unused")
    private ProductLookupResponse fetchProductFallback(Long productId, String bearerToken, Throwable t) {
        throw new ProductUnavailableException(
                "Product catalog is temporarily unavailable — please try again shortly (product " + productId + ")");
    }

    @CircuitBreaker(name = "productSvc", fallbackMethod = "adjustStockFallback")
    public void adjustStock(Long productId, int delta, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of("delta", delta), headers);

        try {
            restTemplate.exchange(
                    productSvcUrl + "/products/{id}/stock", HttpMethod.valueOf("PATCH"), entity,
                    ProductLookupResponse.class, productId);
        } catch (HttpClientErrorException.Conflict e) {
            throw new ProductUnavailableException("Insufficient stock for product " + productId);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductUnavailableException("Product not found: " + productId);
        }
        // Any other RestClientException propagates unwrapped (counts as a breaker failure).
    }

    @SuppressWarnings("unused")
    private void adjustStockFallback(Long productId, int delta, String bearerToken, Throwable t) {
        throw new ProductUnavailableException(
                "Product catalog is temporarily unavailable — please try again shortly (product " + productId + ")");
    }
}
