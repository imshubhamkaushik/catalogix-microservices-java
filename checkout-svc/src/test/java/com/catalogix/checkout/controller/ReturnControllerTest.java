package com.catalogix.checkout.controller;

import com.catalogix.checkout.dto.*;
import com.catalogix.checkout.exception.ForbiddenException;
import com.catalogix.checkout.exception.InvalidReturnException;
import com.catalogix.checkout.exception.RefundFailedException;
import com.catalogix.checkout.exception.ReturnRequestNotFoundException;
import com.catalogix.checkout.model.ReturnStatus;
import com.catalogix.checkout.security.JwtAuthFilter;
import com.catalogix.checkout.security.RateLimiterFilter;
import com.catalogix.checkout.svc.ReturnSvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
@WebMvcTest(
        controllers = ReturnController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class ReturnControllerTest {

    @Autowired private ObjectMapper mapper;
    @Autowired private MockMvc mvc;
    @MockitoBean private ReturnSvc svc;

    private ReturnResponse sampleResponse(ReturnStatus status) {
        ReturnResponse response = new ReturnResponse();

        response.setId(9L);
        response.setOrderId(5L);
        response.setUserId(42L);
        response.setReason("Wrong size");
        response.setStatus(status);
        response.setRefundAmount(new BigDecimal("200.00"));

        if (status != ReturnStatus.REQUESTED) {
                response.setDecisionNote("Refunded: MOCK-REFUND-xyz");
                response.setDecidedAt(Instant.now());
        }

        response.setCreatedAt(Instant.now());

        response.setItems(List.of(
                new ReturnItemResponse(
                        1L,
                        "Phone",
                        2,
                        new BigDecimal("100.00"),
                        new BigDecimal("200.00")
                )
        ));

        return response;
        }

    private RequestReturnRequest sampleRequest() {
        RequestReturnRequest req = new RequestReturnRequest();
        req.setReason("Wrong size");
        ReturnItemRequest item = new ReturnItemRequest();
        item.setProductId(1L);
        item.setQuantity(2);
        req.setItems(List.of(item));
        return req;
    }

    // ---- POST /orders/{orderId}/returns ----

    @Test
    @SuppressWarnings("null")
    void requestReturnReturnsCreated() throws Exception {
        when(svc.requestReturn(eq(5L), eq(42L), eq("USER"), any(RequestReturnRequest.class)))
                .thenReturn(sampleResponse(ReturnStatus.REQUESTED));

        mvc.perform(post("/orders/5/returns")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.refundAmount").value(200.00));
    }

    @Test
    void requestReturnRejectsAnEmptyItemsList() throws Exception {
        RequestReturnRequest req = sampleRequest();
        req.setItems(List.of());

        mvc.perform(post("/orders/5/returns")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestReturnRejectsABlankReason() throws Exception {
        RequestReturnRequest req = sampleRequest();
        req.setReason("");

        mvc.perform(post("/orders/5/returns")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestReturnReturnsConflictWhenTheWindowHasExpired() throws Exception {
        when(svc.requestReturn(eq(5L), eq(42L), eq("USER"), any(RequestReturnRequest.class)))
                .thenThrow(new InvalidReturnException("The 7-day return window for order 5 has expired"));

        mvc.perform(post("/orders/5/returns")
                .requestAttr("userId", 42L)
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isConflict());
    }

    // ---- GET /returns/mine, /returns/{id} ----

    @Test
    void listMineReturnsTheCallersReturns() throws Exception {
        when(svc.listMine(42L)).thenReturn(List.of(sampleResponse(ReturnStatus.REQUESTED)));

        mvc.perform(get("/returns/mine").requestAttr("userId", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getOneReturnsForbiddenWhenNotOwnerOrAdmin() throws Exception {
        when(svc.getOne(9L, 999L, "USER"))
                .thenThrow(new ForbiddenException("You may only view or manage your own returns"));

        mvc.perform(get("/returns/9").requestAttr("userId", 999L).requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getOneReturnsNotFoundForAnUnknownReturn() throws Exception {
        when(svc.getOne(99L, 42L, "USER")).thenThrow(new ReturnRequestNotFoundException(99L));

        mvc.perform(get("/returns/99").requestAttr("userId", 42L).requestAttr("userRole", "USER"))
                .andExpect(status().isNotFound());
    }

    // ---- GET /returns (admin) ----

    @Test
    @SuppressWarnings("null")
    void listAllRejectsNonAdmin() throws Exception {
        mvc.perform(get("/returns").requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("null")
    void listAllReturnsAPagedResponseForAdmin() throws Exception {
        PagedResponse<ReturnResponse> page =
                new PagedResponse<>(List.of(sampleResponse(ReturnStatus.REQUESTED)), 0, 20, 1, 1);
        when(svc.listAll(isNull(), any(Pageable.class))).thenReturn(page);

        mvc.perform(get("/returns").requestAttr("userRole", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    // ---- POST /returns/{id}/approve, /reject ----

    @Test
    void approveRejectsNonAdmin() throws Exception {
        mvc.perform(post("/returns/9/approve").requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("null")
    void approveReturnsTheRefundedReturn() throws Exception {
        when(svc.approve(9L)).thenReturn(sampleResponse(ReturnStatus.REFUNDED));

        mvc.perform(post("/returns/9/approve").requestAttr("userRole", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void approveReturnsBadGatewayWhenRefundFails() throws Exception {
        when(svc.approve(9L)).thenThrow(new RefundFailedException("Refund failed for return 9: timeout"));

        mvc.perform(post("/returns/9/approve").requestAttr("userRole", "ADMIN"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void rejectRejectsNonAdmin() throws Exception {
        RejectReturnRequest req = new RejectReturnRequest();
        req.setReason("Item shows signs of use");

        mvc.perform(post("/returns/9/reject")
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("null")
    void rejectReturnsTheRejectedReturn() throws Exception {
        when(svc.reject(eq(9L), any(RejectReturnRequest.class))).thenReturn(sampleResponse(ReturnStatus.REJECTED));

        RejectReturnRequest req = new RejectReturnRequest();
        req.setReason("Item shows signs of use");

        mvc.perform(post("/returns/9/reject")
                .requestAttr("userRole", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void rejectRequiresAReason() throws Exception {
        RejectReturnRequest req = new RejectReturnRequest();
        req.setReason("");

        mvc.perform(post("/returns/9/reject")
                .requestAttr("userRole", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
