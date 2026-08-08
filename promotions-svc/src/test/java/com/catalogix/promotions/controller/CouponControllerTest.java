package com.catalogix.promotions.controller;

import com.catalogix.promotions.dto.ApplyCouponRequest;
import com.catalogix.promotions.dto.CouponResponse;
import com.catalogix.promotions.dto.CreateCouponRequest;
import com.catalogix.promotions.dto.DiscountResponse;
import com.catalogix.promotions.exception.CouponInvalidException;
import com.catalogix.promotions.model.DiscountType;
import com.catalogix.promotions.security.JwtAuthFilter;
import com.catalogix.promotions.security.RateLimiterFilter;
import com.catalogix.promotions.svc.CouponSvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
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

    private CreateCouponRequest sampleCreateRequest() {
        CreateCouponRequest req = new CreateCouponRequest();
        req.setCode("SAVE10");
        req.setDiscountType(DiscountType.PERCENTAGE);
        req.setDiscountValue(BigDecimal.TEN);
        return req;
    }

    private CouponResponse sampleCoupon() {
        return new CouponResponse(1L, "SAVE10", DiscountType.PERCENTAGE, BigDecimal.TEN,
                null, 0, null, true, Instant.now());
    }

    // ---- internal preview/commit/release, called by cart-svc/checkout-svc ----

    @Test
    @SuppressWarnings("null")
    void previewReturnsDiscount() throws Exception {
        ApplyCouponRequest req = new ApplyCouponRequest();
        req.setSubtotal(new BigDecimal("200.00"));
        when(svc.preview(eq("SAVE10"), any(BigDecimal.class)))
                .thenReturn(new DiscountResponse("SAVE10", new BigDecimal("20.00")));

        mvc.perform(post("/promotions/SAVE10/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountAmount").value(20.00));
    }

    @Test
    @SuppressWarnings("null")
    void previewReturnsConflictForInvalidCoupon() throws Exception {
        ApplyCouponRequest req = new ApplyCouponRequest();
        req.setSubtotal(new BigDecimal("200.00"));
        when(svc.preview(eq("BAD"), any(BigDecimal.class)))
                .thenThrow(new CouponInvalidException("Coupon code not found: BAD"));

        mvc.perform(post("/promotions/BAD/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    @SuppressWarnings("null")
    void commitReturnsDiscount() throws Exception {
        ApplyCouponRequest req = new ApplyCouponRequest();
        req.setSubtotal(new BigDecimal("200.00"));
        when(svc.commit(eq("SAVE10"), any(BigDecimal.class)))
                .thenReturn(new DiscountResponse("SAVE10", new BigDecimal("20.00")));

        mvc.perform(post("/promotions/SAVE10/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SAVE10"));
    }

    @Test
    void releaseReturnsNoContent() throws Exception {
        mvc.perform(post("/promotions/SAVE10/release"))
                .andExpect(status().isNoContent());

        verify(svc).release("SAVE10");
    }

    // ---- admin coupon management ----

    @Test
    @SuppressWarnings("null")
    void createAllowsAdmin() throws Exception {
        when(svc.create(org.mockito.ArgumentMatchers.any())).thenReturn(sampleCoupon());

        mvc.perform(post("/coupons")
                .requestAttr("userRole", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SAVE10"));
    }

    @Test
    void createRejectsNonAdmin() throws Exception {
        mvc.perform(post("/coupons")
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleCreateRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAllowsAdmin() throws Exception {
        when(svc.listAll()).thenReturn(List.of(sampleCoupon()));

        mvc.perform(get("/coupons").requestAttr("userRole", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void listRejectsNonAdmin() throws Exception {
        mvc.perform(get("/coupons").requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("null")
    void deactivateAllowsAdmin() throws Exception {
        when(svc.deactivate(1L)).thenReturn(new CouponResponse(1L, "SAVE10", DiscountType.PERCENTAGE,
                BigDecimal.TEN, null, 0, null, false, Instant.now()));

        mvc.perform(patch("/coupons/1/deactivate").requestAttr("userRole", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void deactivateRejectsNonAdmin() throws Exception {
        mvc.perform(patch("/coupons/1/deactivate").requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }
}
