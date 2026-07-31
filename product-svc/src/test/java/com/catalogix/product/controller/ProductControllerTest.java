package com.catalogix.product.controller;

import com.catalogix.product.dto.CreateProductRequest;
import com.catalogix.product.dto.PagedResponse;
import com.catalogix.product.dto.ProductResponse;
import com.catalogix.product.dto.StockAdjustmentRequest;
import com.catalogix.product.exception.ForbiddenException;
import com.catalogix.product.exception.InsufficientStockException;
import com.catalogix.product.security.JwtAuthFilter;
import com.catalogix.product.security.RateLimiterFilter;
import com.catalogix.product.svc.ProductSvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
@WebMvcTest(
        controllers = ProductController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class ProductControllerTest {

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private ProductSvc svc;

    @Autowired
    private MockMvc mvc;

    @Test
    void listReturnsOk() throws Exception {
        PagedResponse<ProductResponse> empty = new PagedResponse<>(Collections.emptyList(), 0, 20, 0, 0);
        when(svc.search(any(), any(), any(Pageable.class))).thenReturn(empty);

        mvc.perform(get("/products").requestAttr("userId", 123L).requestAttr("userRole", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    @SuppressWarnings("null")
    void createReturnsCreated() throws Exception {
        CreateProductRequest req = new CreateProductRequest();
        req.setName("Phone");
        req.setDescription("Nice phone");
        req.setPrice(new BigDecimal("100.00"));
        req.setCategory("electronics");
        req.setStockQuantity(10);

        when(svc.create(any(CreateProductRequest.class), eq(123L)))
                .thenReturn(new ProductResponse(1L, "Phone", "Nice phone", new BigDecimal("100.00"),
                        "electronics", 10, 123L, Instant.now()));

        mvc.perform(post("/products")
                .requestAttr("userId", 123L)
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("Phone"))
                .andExpect(jsonPath("$.price").value(100.00))
                .andExpect(jsonPath("$.category").value("electronics"));
    }

    @Test
    void getOneReturnsNotFoundWhenMissing() throws Exception {
        when(svc.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(get("/products/99").requestAttr("userId", 123L).requestAttr("userRole", "USER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsNoContentForOwner() throws Exception {
        when(svc.deleteById(1L, 123L, "USER")).thenReturn(true);
        mvc.perform(delete("/products/1").requestAttr("userId", 123L).requestAttr("userRole", "USER"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturnsNotFoundWhenMissing() throws Exception {
        when(svc.deleteById(99L, 123L, "USER")).thenReturn(false);
        mvc.perform(delete("/products/99").requestAttr("userId", 123L).requestAttr("userRole", "USER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsForbiddenForNonOwnerNonAdmin() throws Exception {
        when(svc.deleteById(1L, 456L, "USER"))
                .thenThrow(new ForbiddenException("Only the product's owner or an admin may delete it"));
        mvc.perform(delete("/products/1").requestAttr("userId", 456L).requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("null")
    void adjustStockReturnsOk() throws Exception {
        StockAdjustmentRequest req = new StockAdjustmentRequest(-2);
        when(svc.adjustStock(1L, -2)).thenReturn(
                new ProductResponse(1L, "Phone", "Nice phone", new BigDecimal("100.00"),
                        "electronics", 8, 123L, Instant.now()));

        mvc.perform(patch("/products/1/stock")
                .requestAttr("userId", 123L)
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(8));
    }

    @Test
    @SuppressWarnings("null")
    void adjustStockReturnsConflictWhenInsufficient() throws Exception {
        StockAdjustmentRequest req = new StockAdjustmentRequest(-100);
        when(svc.adjustStock(1L, -100)).thenThrow(new InsufficientStockException(1L, 8, 100));

        mvc.perform(patch("/products/1/stock")
                .requestAttr("userId", 123L)
                .requestAttr("userRole", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }
}
