package com.catalogix.checkout.client;

import com.catalogix.checkout.dto.PayOrderRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

/**
 * checkout-svc is the ONLY caller of payment-svc — no gateway route is
 * exposed for it, and the browser never talks to it directly. A decline
 * (HTTP 402) is a legitimate business outcome, not a transport failure, so
 * it's translated into a normal return value here rather than an exception.
 */
@Component
public class PaymentClient {

    private final RestTemplate restTemplate;
    private final String paymentSvcUrl;

    public PaymentClient(RestTemplate restTemplate, @Value("${PAYMENT_SVC_URL}") String paymentSvcUrl) {
        this.restTemplate = restTemplate;
        this.paymentSvcUrl = paymentSvcUrl;
    }

    public record PaymentOutcome(boolean succeeded, String reference) {}

    public PaymentOutcome process(Long orderId, BigDecimal amount, PayOrderRequest req, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        var body = new java.util.HashMap<String, Object>();
        body.put("orderId", orderId);
        body.put("amount", amount);
        body.put("method", req.getMethod());
        body.put("cardLast4", req.getCardLast4());

        try {
            var resp = restTemplate.exchange(paymentSvcUrl + "/payments", HttpMethod.POST,
                    new HttpEntity<>(body, headers), RawPayment.class);
            RawPayment p = resp.getBody();
            return new PaymentOutcome(true, p != null ? p.reference : null);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.PAYMENT_REQUIRED) {
                return new PaymentOutcome(false, null);
            }
            throw e;
        }
    }

    static class RawPayment {
        public String reference;
        public String status;
    }
}
