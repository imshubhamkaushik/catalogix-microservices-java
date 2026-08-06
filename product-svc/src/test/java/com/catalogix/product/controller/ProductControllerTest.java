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

    private static final String USER_ID_ATTR = "userId";
    private static final String USER_ROLE_ATTR = "userRole";
    private static final Long TEST_USER_ID = 123L;
    private static final String TEST_USER_ROLE = "USER";
    private static final String TEST_PRODUCT_NAME = "Phone";
    private static final String TEST_PRODUCT_DESCRIPTION = "Nice phone";
    private static final String TEST_PRODUCT_CATEGORY = "electronics";
    private static final java.math.BigDecimal TEST_PRODUCT_PRICE = new java.math.BigDecimal("100.00");

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

        mvc.perform(get("/products").requestAttr(USER_ID_ATTR, TEST_USER_ID).requestAttr(USER_ROLE_ATTR, TEST_USER_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    @SuppressWarnings("null")
    void createReturnsCreated() throws Exception {
        CreateProductRequest req = new CreateProductRequest();
        req.setName(TEST_PRODUCT_NAME);
        req.setDescription(TEST_PRODUCT_DESCRIPTION);
        req.setPrice(TEST_PRODUCT_PRICE);
        req.setCategory(TEST_PRODUCT_CATEGORY);
        req.setStockQuantity(10);

        when(svc.create(any(CreateProductRequest.class), eq(TEST_USER_ID)))
                .thenReturn(new ProductResponse(1L, TEST_PRODUCT_NAME, TEST_PRODUCT_DESCRIPTION, TEST_PRODUCT_PRICE,
                        TEST_PRODUCT_CATEGORY, 10, TEST_USER_ID, Instant.now()));

        mvc.perform(post("/products")
                .requestAttr(USER_ID_ATTR, TEST_USER_ID)
                .requestAttr(USER_ROLE_ATTR, TEST_USER_ROLE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value(TEST_PRODUCT_NAME))
                .andExpect(jsonPath("$.price").value(100.00))
                .andExpect(jsonPath("$.category").value(TEST_PRODUCT_CATEGORY));
    }

    @Test
    void getOneReturnsNotFoundWhenMissing() throws Exception {
        when(svc.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(get("/products/99").requestAttr(USER_ID_ATTR, TEST_USER_ID).requestAttr(USER_ROLE_ATTR, TEST_USER_ROLE))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsNoContentForOwner() throws Exception {
        when(svc.deleteById(1L, TEST_USER_ID, TEST_USER_ROLE)).thenReturn(true);
        mvc.perform(delete("/products/1").requestAttr(USER_ID_ATTR, TEST_USER_ID).requestAttr(USER_ROLE_ATTR, TEST_USER_ROLE))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturnsNotFoundWhenMissing() throws Exception {
        when(svc.deleteById(99L, TEST_USER_ID, TEST_USER_ROLE)).thenReturn(false);
        mvc.perform(delete("/products/99").requestAttr(USER_ID_ATTR, TEST_USER_ID).requestAttr(USER_ROLE_ATTR, TEST_USER_ROLE))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsForbiddenForNonOwnerNonAdmin() throws Exception {
        when(svc.deleteById(1L, 456L, TEST_USER_ROLE))
                .thenThrow(new ForbiddenException("Only the product's owner or an admin may delete it"));
        mvc.perform(delete("/products/1").requestAttr(USER_ID_ATTR, 456L).requestAttr(USER_ROLE_ATTR, TEST_USER_ROLE))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("null")
    void adjustStockReturnsOk() throws Exception {
        StockAdjustmentRequest req = new StockAdjustmentRequest(-2);
        when(svc.adjustStock(1L, -2)).thenReturn(
                new ProductResponse(1L, TEST_PRODUCT_NAME, TEST_PRODUCT_DESCRIPTION, new BigDecimal("100.00"),
                        TEST_PRODUCT_CATEGORY, 8, TEST_USER_ID, Instant.now()));

        mvc.perform(patch("/products/1/stock")
                .requestAttr(USER_ID_ATTR, TEST_USER_ID)
                .requestAttr(USER_ROLE_ATTR, TEST_USER_ROLE)
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
                .requestAttr(USER_ID_ATTR, TEST_USER_ID)
                .requestAttr(USER_ROLE_ATTR, TEST_USER_ROLE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }
}
