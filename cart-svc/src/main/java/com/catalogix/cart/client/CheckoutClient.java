package com.catalogix.cart.client;

import com.catalogix.cart.dto.CheckoutRequestForward;
import com.catalogix.cart.exception.CheckoutFailedException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Forwards the cart's contents to checkout-svc's public POST /orders — no
 * circuit-breaker fallback here, deliberately: placing an order is not a
 * degradable operation like a price lookup, there is no sensible fallback
 * other than "tell the user it failed." checkout-svc's own resilience
 * (its calls onward to inventory/promotions/payment) is what actually needs
 * to fail fast; this hop just needs to pass through whatever it says.
 */
@Component
public class CheckoutClient {

    private final RestTemplate restTemplate;
    private final String checkoutSvcUrl;

    public CheckoutClient(RestTemplate restTemplate, @Value("${CHECKOUT_SVC_URL}") String checkoutSvcUrl) {
        this.restTemplate = restTemplate;
        this.checkoutSvcUrl = checkoutSvcUrl;
    }

    public ResponseEntity<String> createOrder(CheckoutRequestForward req, String bearerToken, String userEmail,
                                               String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        HttpEntity<CheckoutRequestForward> entity = new HttpEntity<>(req, headers);

        try {
            return restTemplate.exchange(
                    checkoutSvcUrl + "/orders", HttpMethod.POST, entity, String.class);
        } catch (HttpStatusCodeException e) {
            // checkout-svc rejected the order (out of stock, bad coupon, payment
            // step, etc.) — its response is already a well-formed, user-facing
            // error body. Pass it straight through rather than re-wrapping it.
            throw new CheckoutFailedException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new CheckoutFailedException(503,
                    "{\"error\":\"Checkout is temporarily unavailable — please try again shortly\"}");
        }
    }
}
