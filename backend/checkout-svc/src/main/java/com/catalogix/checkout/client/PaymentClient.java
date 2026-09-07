package com.catalogix.checkout.client;

import com.catalogix.checkout.dto.PayOrderRequest;
import com.catalogix.security.JwtService;
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
 *
 * SECURITY FIX: this used to forward the end user's own bearer token, which
 * meant payment-svc had to accept regular user tokens on its one and only
 * endpoint — despite explicitly not re-checking order ownership itself
 * (it trusted checkout-svc to have already done that). Any authenticated
 * user could call payment-svc directly with an arbitrary orderId/amount and
 * have it recorded as a "real" payment attempt. Every call now mints a
 * short-lived system token instead, and passes the real user id explicitly
 * in the body (requestedByUserId) purely for payment-svc's audit trail,
 * since the system token's own subject is a sentinel, not a real user.
 */
@Component
public class PaymentClient {

    private final RestTemplate restTemplate;
    private final String paymentSvcUrl;
    private final JwtService jwtService;

    public PaymentClient(RestTemplate restTemplate, @Value("${PAYMENT_SVC_URL}") String paymentSvcUrl,
                          JwtService jwtService) {
        this.restTemplate = restTemplate;
        this.paymentSvcUrl = paymentSvcUrl;
        this.jwtService = jwtService;
    }

    public record PaymentOutcome(boolean succeeded, String reference, String status) {}

    public PaymentOutcome process(Long orderId, Long requestedByUserId, BigDecimal amount, PayOrderRequest req) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateSystemToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        var body = new java.util.HashMap<String, Object>();
        body.put("orderId", orderId);
        body.put("requestedByUserId", requestedByUserId);
        body.put("amount", amount);
        body.put("method", req.getMethod());
        body.put("cardLast4", req.getCardLast4());
        body.put("upiId", req.getUpiId());

        try {
            var resp = restTemplate.exchange(paymentSvcUrl + "/payments", HttpMethod.POST,
                    new HttpEntity<>(body, headers), RawPayment.class);
            RawPayment p = resp.getBody();
            return new PaymentOutcome(true, p != null ? p.reference : null, p != null ? p.status : null);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.PAYMENT_REQUIRED) {
                return new PaymentOutcome(false, null, null);
            }
            throw e;
        }
    }

    static class RawPayment {
        public String reference;
        public String status;
    }
}
