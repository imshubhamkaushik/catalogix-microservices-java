package com.catalogix.payment.controller;

import com.catalogix.payment.dto.PaymentResponse;
import com.catalogix.payment.dto.ProcessPaymentRequest;
import com.catalogix.payment.exception.DeclinedException;
import com.catalogix.payment.model.PaymentStatus;
import com.catalogix.payment.security.JwtAuthFilter;
import com.catalogix.payment.security.RateLimiterFilter;
import com.catalogix.payment.svc.PaymentSvc;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
// No gateway route exists for this service (see PaymentController's Javadoc) — the only
// caller is checkout-svc, forwarding the end user's own token.
@WebMvcTest(
        controllers = PaymentController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class PaymentControllerTest {

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private PaymentSvc svc;

    @Autowired
    private MockMvc mvc;

    private ProcessPaymentRequest sampleRequest(String cardLast4) {
        ProcessPaymentRequest req = new ProcessPaymentRequest();
        req.setOrderId(5L);
        req.setAmount(new BigDecimal("200.00"));
        req.setMethod("MOCK_CARD");
        req.setCardLast4(cardLast4);
        return req;
    }

    @Test
    @SuppressWarnings("null")
    void processReturnsCreatedOnSuccess() throws Exception {
        PaymentResponse resp = new PaymentResponse(1L, 5L, new BigDecimal("200.00"), "MOCK_CARD",
                PaymentStatus.SUCCEEDED, "MOCK-REF", Instant.now());
        when(svc.process(any(ProcessPaymentRequest.class), eq(42L))).thenReturn(resp);

        mvc.perform(post("/payments")
                .requestAttr("userId", 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest("4242"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.reference").value("MOCK-REF"));
    }

    @Test
    @SuppressWarnings("null")
    void processReturnsPaymentRequiredOnDecline() throws Exception {
        // A decline is DeclinedException thrown from the service layer, not
        // a normal return value here — GlobalExceptionHandler maps it to 402
        // so checkout-svc's PaymentClient can tell "declined" apart from
        // "this call itself failed" by status code alone.
        when(svc.process(any(ProcessPaymentRequest.class), eq(42L)))
                .thenThrow(new DeclinedException("Card declined"));

        mvc.perform(post("/payments")
                .requestAttr("userId", 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest("0000"))))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void processRejectsMissingOrderId() throws Exception {
        ProcessPaymentRequest req = sampleRequest("4242");
        req.setOrderId(null);

        mvc.perform(post("/payments")
                .requestAttr("userId", 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void processRejectsNonPositiveAmount() throws Exception {
        ProcessPaymentRequest req = sampleRequest("4242");
        req.setAmount(BigDecimal.ZERO);

        mvc.perform(post("/payments")
                .requestAttr("userId", 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void processRejectsBlankMethod() throws Exception {
        ProcessPaymentRequest req = sampleRequest("4242");
        req.setMethod("");

        mvc.perform(post("/payments")
                .requestAttr("userId", 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
