package com.catalogix.checkout.client;

import com.catalogix.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

/**
 * checkout-svc's only caller is ReturnSvc#approve, and only for CARD/UPI
 * orders — a COD order never reaches this client at all, since there's no
 * captured payment to reverse (see ReturnSvc's Javadoc). Mints a system
 * token the same way PaymentClient does, for the same reason: this is a
 * privileged, money-moving call that must not be reachable by a regular
 * user's own token.
 */
@Component
public class RefundClient {

    private final RestTemplate restTemplate;
    private final String paymentSvcUrl;
    private final JwtService jwtService;

    public RefundClient(RestTemplate restTemplate, @Value("${PAYMENT_SVC_URL}") String paymentSvcUrl,
                         JwtService jwtService) {
        this.restTemplate = restTemplate;
        this.paymentSvcUrl = paymentSvcUrl;
        this.jwtService = jwtService;
    }

    public record RefundOutcome(String reference) {}

    public RefundOutcome refund(Long orderId, BigDecimal amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateSystemToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        var body = new java.util.HashMap<String, Object>();
        body.put("orderId", orderId);
        body.put("amount", amount);

        var resp = restTemplate.exchange(paymentSvcUrl + "/payments/refund", HttpMethod.POST,
                new HttpEntity<>(body, headers), RawRefund.class);
        RawRefund r = resp.getBody();
        return new RefundOutcome(r != null ? r.reference : null);
    }

    static class RawRefund {
        public String reference;
    }
}
