package com.catalogix.order.controller;

import com.catalogix.order.dto.CouponResponse;
import com.catalogix.order.dto.CreateCouponRequest;
import com.catalogix.order.model.DiscountType;
import com.catalogix.order.security.JwtAuthFilter;
import com.catalogix.order.security.RateLimiterFilter;
import com.catalogix.order.svc.CouponSvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Objects;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = CouponController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class CouponControllerTest {

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private CouponSvc svc;

    @Autowired
    private MockMvc mvc;

    private static final String COUPON_SAVE10 = "SAVE10";

    private CreateCouponRequest sampleRequest() {
        CreateCouponRequest req = new CreateCouponRequest();
        req.setCode(COUPON_SAVE10);
        req.setDiscountType(DiscountType.PERCENTAGE);
        req.setDiscountValue(BigDecimal.TEN);
        return req;
    }

    @Test
    @SuppressWarnings("null")
    void createAllowsAdmin() throws Exception {
        when(svc.create(any())).thenReturn(new CouponResponse(
                1L, COUPON_SAVE10, DiscountType.PERCENTAGE, BigDecimal.TEN, null, 0, null, true, Instant.now()));

        mvc.perform(post("/coupons")
                .requestAttr("userRole", "ADMIN")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                .content(Objects.requireNonNull(mapper.writeValueAsString(sampleRequest()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SAVE10"));
    }

    @Test
    void createRejectsNonAdmin() throws Exception {
        mvc.perform(post("/coupons")
                .requestAttr("userRole", "USER")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                .content(Objects.requireNonNull(mapper.writeValueAsString(sampleRequest()))))
                .andExpect(status().isForbidden());
    }
}
