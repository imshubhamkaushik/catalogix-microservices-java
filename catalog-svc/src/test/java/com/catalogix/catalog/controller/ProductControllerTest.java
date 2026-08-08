package com.catalogix.catalog.controller;

import com.catalogix.catalog.dto.CreateProductRequest;
import com.catalogix.catalog.dto.PagedResponse;
import com.catalogix.catalog.dto.ProductResponse;
import com.catalogix.catalog.dto.StockAdjustmentRequest;
import com.catalogix.catalog.exception.ForbiddenException;
import com.catalogix.catalog.exception.ProductNotFoundException;
import com.catalogix.catalog.security.JwtAuthFilter;
import com.catalogix.catalog.security.RateLimiterFilter;
import com.catalogix.catalog.svc.ProductSvc;
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
import java.util.Optional;

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

    private CreateProductRequest sampleRequest() {
        CreateProductRequest req = new CreateProductRequest();
        req.setName("Phone");
        req.setPrice(new BigDecimal("100.00"));
        req.setStockQuantity(10);
        return req;
    }

    private ProductResponse sampleResponse() {
        return new ProductResponse(1L, "Phone", "A phone", new BigDecimal("100.00"),
                "GENERAL", 10, 42L, Instant.now());
    }

    @Test
    void listReturnsPagedProducts() throws Exception {
        PagedResponse<ProductResponse> page = new PagedResponse<>(List.of(sampleResponse()), 0, 20, 1, 1);
        when(svc.search(isNull(), isNull(), any(Pageable.class), isNull())).thenReturn(page);

        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("Phone"));
    }

    @Test
    @SuppressWarnings("null")
    void createReturnsCreatedWithLocationHeader() throws Exception {
        when(svc.create(any(CreateProductRequest.class), eq(42L), eq("Bearer token")))
                .thenReturn(sampleResponse());

        mvc.perform(post("/products")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/products/1")))
                .andExpect(jsonPath("$.name").value("Phone"));
    }

    @Test
    void createRejectsMissingName() throws Exception {
        CreateProductRequest req = sampleRequest();
        req.setName(null);

        mvc.perform(post("/products")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsNonPositivePrice() throws Exception {
        CreateProductRequest req = sampleRequest();
        req.setPrice(BigDecimal.ZERO);

        mvc.perform(post("/products")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOneReturnsProductWhenFound() throws Exception {
        when(svc.findById(1L, null)).thenReturn(Optional.of(sampleResponse()));

        mvc.perform(get("/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    void getOneReturnsNotFoundWhenMissing() throws Exception {
        when(svc.findById(99L, null)).thenReturn(Optional.empty());

        mvc.perform(get("/products/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsNoContentOnSuccess() throws Exception {
        when(svc.deleteById(1L, 42L, "USER")).thenReturn(true);

        mvc.perform(delete("/products/1").requestAttr("userId", 42L).requestAttr("userRole", "USER"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturnsNotFoundWhenProductDoesNotExist() throws Exception {
        when(svc.deleteById(99L, 42L, "USER")).thenReturn(false);

        mvc.perform(delete("/products/99").requestAttr("userId", 42L).requestAttr("userRole", "USER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsForbiddenForNonOwnerNonAdmin() throws Exception {
        when(svc.deleteById(1L, 7L, "USER"))
                .thenThrow(new ForbiddenException("Only the product's owner or an admin may delete it"));

        mvc.perform(delete("/products/1").requestAttr("userId", 7L).requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("null")
    void adjustStockReturnsUpdatedProduct() throws Exception {
        ProductResponse restocked = new ProductResponse(1L, "Phone", "A phone", new BigDecimal("100.00"),
                "GENERAL", 15, 42L, Instant.now());
        when(svc.adjustStock(eq(1L), eq(5), eq("Bearer token"))).thenReturn(restocked);

        mvc.perform(patch("/products/1/stock")
                .requestAttr("bearerToken", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new StockAdjustmentRequest(5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(15));
    }

    @Test
    void adjustStockReturnsNotFoundWhenProductDoesNotExist() throws Exception {
        when(svc.adjustStock(eq(99L), eq(5), eq("Bearer token")))
                .thenThrow(new ProductNotFoundException(99L));

        mvc.perform(patch("/products/99/stock")
                .requestAttr("bearerToken", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new StockAdjustmentRequest(5))))
                .andExpect(status().isNotFound());
    }

    @Test
    void adjustStockRejectsMissingDelta() throws Exception {
        mvc.perform(patch("/products/1/stock")
                .requestAttr("bearerToken", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
