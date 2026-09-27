package com.catalogix.checkout.client;

import com.catalogix.checkout.exception.CouponInvalidException;
import com.catalogix.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

@Component
public class PromotionsClient {

    private final RestTemplate restTemplate;
    private final String promotionsSvcUrl;
    private final JwtService jwtService;

    public PromotionsClient(
            RestTemplate restTemplate,
            @Value("${PROMOTIONS_SVC_URL}") String promotionsSvcUrl,
            JwtService jwtService) {
        this.restTemplate = restTemplate;
        this.promotionsSvcUrl = promotionsSvcUrl;
        this.jwtService = jwtService;
    }

    public record DiscountDto(String code, BigDecimal discountAmount) {
    }

    // The one moment a coupon actually gets redeemed — atomic on
    // promotions-svc's side (row-locked), see that service's CouponSvc.commit.
    public DiscountDto commit(String code, BigDecimal subtotal) {
        return commit(code, subtotal, null);
    }

    public DiscountDto commit(
            String code,
            BigDecimal subtotal,
            String operationId) {

        try {
            var resp = exchange(
                    "/promotions/" + code + "/commit",
                    subtotal,
                    systemHeaders(operationId));

            return new DiscountDto(resp.code, resp.discountAmount);
        } catch (HttpClientErrorException.Conflict
                | HttpClientErrorException.NotFound e) {
            throw new CouponInvalidException(
                    "Coupon is not valid: " + code);
        }
    }

    public void release(String code) {
        release(code, null);
    }

    public void release(
            String code,
            String operationId) {

        HttpHeaders headers = systemHeaders(operationId);

        restTemplate.exchange(
                promotionsSvcUrl + "/promotions/" + code + "/release",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Void.class);
    }

    private RawDiscount exchange(
            String path,
            BigDecimal subtotal,
            HttpHeaders headers) {

        headers.setContentType(MediaType.APPLICATION_JSON);

        var body = new java.util.HashMap<String, Object>();
        body.put("subtotal", subtotal);

        var resp = restTemplate.exchange(
                promotionsSvcUrl + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                RawDiscount.class);

        RawDiscount responseBody = resp.getBody();

        if (responseBody == null) {
            throw new IllegalStateException(
                    "promotions-svc returned an empty discount response");
        }

        return responseBody;
    }

    private HttpHeaders systemHeaders(String operationId) {
        HttpHeaders headers = new HttpHeaders();

        headers.set(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + jwtService.generateSystemToken());

        if (operationId != null && !operationId.isBlank()) {
            headers.set("X-Operation-Id", operationId.trim());
        }

        return headers;
    }

    static class RawDiscount {
        public String code;
        public BigDecimal discountAmount;
    }
}