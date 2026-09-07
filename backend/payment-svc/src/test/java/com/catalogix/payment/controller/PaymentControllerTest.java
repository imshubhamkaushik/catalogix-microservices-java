package com.catalogix.payment.controller;

import com.catalogix.payment.dto.PaymentResponse;
import com.catalogix.payment.dto.ProcessPaymentRequest;
import com.catalogix.payment.dto.ProcessRefundRequest;
import com.catalogix.payment.dto.RefundResponse;
import com.catalogix.payment.exception.DeclinedException;
import com.catalogix.payment.model.PaymentMethod;
import com.catalogix.payment.model.PaymentStatus;
import com.catalogix.security.JwtAuthFilter;
import com.catalogix.security.RateLimiterFilter;
import com.catalogix.payment.svc.PaymentSvc;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
// caller is checkout-svc, which now mints a SYSTEM-role token per call (see checkout-svc's
// PaymentClient) rather than forwarding the end user's own token.
@WebMvcTest(
        controllers = PaymentController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class PaymentControllerTest {

    @Autowired
    private JsonMapper mapper;

    @MockitoBean
    private PaymentSvc svc;

    @Autowired
    private MockMvc mvc;

    private ProcessPaymentRequest sampleRequest(String cardLast4) {
        ProcessPaymentRequest req = new ProcessPaymentRequest();
        req.setOrderId(5L);
        req.setRequestedByUserId(42L);
        req.setAmount(new BigDecimal("200.00"));
        req.setMethod(PaymentMethod.CARD);
        req.setCardLast4(cardLast4);
        return req;
    }

    @Test
    @SuppressWarnings("null")
    void processReturnsCreatedOnSuccess() throws Exception {
        PaymentResponse resp = new PaymentResponse(1L, 5L, new BigDecimal("200.00"), PaymentMethod.CARD,
                PaymentStatus.SUCCEEDED, "MOCK-REF", Instant.now());
        when(svc.process(any(ProcessPaymentRequest.class), eq(42L))).thenReturn(resp);

        mvc.perform(post("/payments")
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest("4242"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.reference").value("MOCK-REF"));
    }

    // Added with multiple payment methods: COD returns 201 with a
    // COD_PENDING status, not a decline — there's no failure path for it at all.
    @Test
    @SuppressWarnings("null")
    void processReturnsCreatedWithCodPendingForCod() throws Exception {
        PaymentResponse resp = new PaymentResponse(1L, 5L, new BigDecimal("200.00"), PaymentMethod.COD,
                PaymentStatus.COD_PENDING, null, Instant.now());
        when(svc.process(any(ProcessPaymentRequest.class), eq(42L))).thenReturn(resp);

        ProcessPaymentRequest req = new ProcessPaymentRequest();
        req.setOrderId(5L);
        req.setRequestedByUserId(42L);
        req.setAmount(new BigDecimal("200.00"));
        req.setMethod(PaymentMethod.COD);

        mvc.perform(post("/payments")
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COD_PENDING"));
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
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest("0000"))))
                .andExpect(status().isPaymentRequired());
    }

    // Added with the SYSTEM-role fix: any caller other than checkout-svc's
    // own minted token must now be rejected outright, before svc.process()
    // is ever reached — this used to be reachable by any authenticated user.
    @Test
    void processRejectsNonSystemCaller() throws Exception {
        mvc.perform(post("/payments")
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest("4242"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void processRejectsMissingOrderId() throws Exception {
        ProcessPaymentRequest req = sampleRequest("4242");
        req.setOrderId(null);

        mvc.perform(post("/payments")
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void processRejectsMissingRequestedByUserId() throws Exception {
        ProcessPaymentRequest req = sampleRequest("4242");
        req.setRequestedByUserId(null);

        mvc.perform(post("/payments")
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void processRejectsNonPositiveAmount() throws Exception {
        ProcessPaymentRequest req = sampleRequest("4242");
        req.setAmount(BigDecimal.ZERO);

        mvc.perform(post("/payments")
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // Replaces the old processRejectsBlankMethod test — method is a real
    // enum now, so "blank" isn't a meaningful case anymore; an unrecognized
    // value is the equivalent failure mode, and exercises the
    // HttpMessageNotReadableException handler added alongside this feature.
    @Test
    void processRejectsAnUnrecognizedMethodValue() throws Exception {
        String badJson = """
                {"orderId":5,"requestedByUserId":42,"amount":200.00,"method":"BITCOIN","cardLast4":"4242"}
                """;

        mvc.perform(post("/payments")
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(badJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void processRejectsAMissingMethod() throws Exception {
        String json = """
                {"orderId":5,"requestedByUserId":42,"amount":200.00}
                """;

        mvc.perform(post("/payments")
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest());
    }

    // ---- /payments/refund ----

    @Test
    @SuppressWarnings("null")
    void refundReturnsCreated() throws Exception {
        when(svc.refund(any(ProcessRefundRequest.class)))
                .thenReturn(new RefundResponse(1L, 5L, new BigDecimal("100.00"), "MOCK-REFUND-abc", Instant.now()));

        ProcessRefundRequest req = new ProcessRefundRequest();
        req.setOrderId(5L);
        req.setAmount(new BigDecimal("100.00"));

        mvc.perform(post("/payments/refund")
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("MOCK-REFUND-abc"));
    }

    @Test
    void refundRejectsNonSystemCaller() throws Exception {
        ProcessRefundRequest req = new ProcessRefundRequest();
        req.setOrderId(5L);
        req.setAmount(new BigDecimal("100.00"));

        mvc.perform(post("/payments/refund")
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void refundReturnsNotFoundWhenThereIsNoOriginalPayment() throws Exception {
        when(svc.refund(any(ProcessRefundRequest.class)))
                .thenThrow(new com.catalogix.payment.exception.NoSuchPaymentException(5L));

        ProcessRefundRequest req = new ProcessRefundRequest();
        req.setOrderId(5L);
        req.setAmount(new BigDecimal("100.00"));

        mvc.perform(post("/payments/refund")
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    void refundRejectsMissingAmount() throws Exception {
        ProcessRefundRequest req = new ProcessRefundRequest();
        req.setOrderId(5L);

        mvc.perform(post("/payments/refund")
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
