package com.catalogix.cart.client;

import com.catalogix.cart.exception.ProductUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

/**
 * Cart-side coupon handling only ever calls preview() — a read-only "would
 * this work, and for how much" check. The actual redemption (commit) is
 * promotions-svc's contract with checkout-svc alone; cart-svc never mutates
 * coupon usage.
 */
@Component
public class PromotionsClient {

    private final RestTemplate restTemplate;
    private final String promotionsSvcUrl;

    public PromotionsClient(RestTemplate restTemplate,
                             @Value("${PROMOTIONS_SVC_URL}") String promotionsSvcUrl) {
        this.restTemplate = restTemplate;
        this.promotionsSvcUrl = promotionsSvcUrl;
    }

    public BigDecimal preview(String code, BigDecimal subtotal, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = new java.util.HashMap<String, Object>();
        body.put("subtotal", subtotal);
        try {
            var resp = restTemplate.exchange(
                    promotionsSvcUrl + "/promotions/" + code + "/preview",
                    HttpMethod.POST, new HttpEntity<>(body, headers), DiscountDto.class);
            return resp.getBody() != null ? resp.getBody().discountAmount : BigDecimal.ZERO;
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound e) {
            throw new ProductUnavailableException("Coupon is not valid: " + code);
        }
    }

    static class DiscountDto {
        public String code;
        public BigDecimal discountAmount;
    }
}
