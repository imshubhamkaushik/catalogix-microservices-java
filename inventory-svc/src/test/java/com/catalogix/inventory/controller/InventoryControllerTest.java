package com.catalogix.inventory.controller;

import com.catalogix.inventory.dto.AdjustStockRequest;
import com.catalogix.inventory.dto.InitStockRequest;
import com.catalogix.inventory.dto.StockResponse;
import com.catalogix.inventory.exception.InsufficientStockException;
import com.catalogix.inventory.exception.StockItemNotFoundException;
import com.catalogix.inventory.security.JwtAuthFilter;
import com.catalogix.inventory.security.RateLimiterFilter;
import com.catalogix.inventory.svc.InventorySvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
// This is an internal, non-gateway-routed service (see InventoryController's Javadoc),
// so there's no admin/role check to exercise here — every caller is another service.
@WebMvcTest(
        controllers = InventoryController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class InventoryControllerTest {

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private InventorySvc svc;

    @Autowired
    private MockMvc mvc;

    @Test
    void getReturnsStockForKnownProduct() throws Exception {
        when(svc.get(1L)).thenReturn(new StockResponse(1L, 10));

        mvc.perform(get("/inventory/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(10));
    }

    @Test
    void getReturnsNotFoundForUnknownProduct() throws Exception {
        when(svc.get(99L)).thenThrow(new StockItemNotFoundException(99L));

        mvc.perform(get("/inventory/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void initCreatesStockRecord() throws Exception {
        InitStockRequest req = new InitStockRequest();
        req.setProductId(1L);
        req.setQuantity(10);
        when(svc.init(1L, 10)).thenReturn(new StockResponse(1L, 10));

        mvc.perform(post("/inventory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(10));
    }

    @Test
    void initRejectsMissingProductId() throws Exception {
        InitStockRequest req = new InitStockRequest();
        req.setQuantity(10);

        mvc.perform(post("/inventory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void initRejectsNegativeQuantity() throws Exception {
        InitStockRequest req = new InitStockRequest();
        req.setProductId(1L);
        req.setQuantity(-1);

        mvc.perform(post("/inventory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adjustReservesStockOnNegativeDelta() throws Exception {
        AdjustStockRequest req = new AdjustStockRequest();
        req.setDelta(-2);
        when(svc.adjust(1L, -2)).thenReturn(new StockResponse(1L, 8));

        mvc.perform(patch("/inventory/1/adjust")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(8));
    }

    @Test
    void adjustReturnsConflictWhenStockInsufficient() throws Exception {
        AdjustStockRequest req = new AdjustStockRequest();
        req.setDelta(-100);
        when(svc.adjust(1L, -100)).thenThrow(new InsufficientStockException(1L, 8, 100));

        mvc.perform(patch("/inventory/1/adjust")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    void adjustReturnsNotFoundForUnknownProduct() throws Exception {
        AdjustStockRequest req = new AdjustStockRequest();
        req.setDelta(-1);
        when(svc.adjust(99L, -1)).thenThrow(new StockItemNotFoundException(99L));

        mvc.perform(patch("/inventory/99/adjust")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }
}
