package com.catalogix.checkout.client;

import com.catalogix.checkout.exception.CouponInvalidException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

@Component
public class PromotionsClient {

    private final RestTemplate restTemplate;
    private final String promotionsSvcUrl;

    public PromotionsClient(RestTemplate restTemplate, @Value("${PROMOTIONS_SVC_URL}") String promotionsSvcUrl) {
        this.restTemplate = restTemplate;
        this.promotionsSvcUrl = promotionsSvcUrl;
    }

    public record DiscountDto(String code, BigDecimal discountAmount) {}

    // The one moment a coupon actually gets redeemed — atomic on
    // promotions-svc's side (row-locked), see that service's CouponSvc.commit.
    public DiscountDto commit(String code, BigDecimal subtotal, String bearerToken) {
        try {
            var resp = exchange("/promotions/" + code + "/commit", subtotal, bearerToken);
            return new DiscountDto(resp.code, resp.discountAmount);
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound e) {
            throw new CouponInvalidException("Coupon is not valid: " + code);
        }
    }

    // Compensation — called directly on the live path, or from the outbox
    // on retry. Idempotent on promotions-svc's side would be a further
    // improvement (currently a redelivered release could over-release);
    // left as a follow-up, same caveat outbox-driven compensation has for
    // inventory in the original design.
    public void release(String code, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        restTemplate.exchange(promotionsSvcUrl + "/promotions/" + code + "/release",
                HttpMethod.POST, new HttpEntity<>(headers), Void.class);
    }

    private RawDiscount exchange(String path, BigDecimal subtotal, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = new java.util.HashMap<String, Object>();
        body.put("subtotal", subtotal);
        var resp = restTemplate.exchange(promotionsSvcUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), RawDiscount.class);
        return resp.getBody();
    }

    static class RawDiscount {
        public String code;
        public BigDecimal discountAmount;
    }
}
