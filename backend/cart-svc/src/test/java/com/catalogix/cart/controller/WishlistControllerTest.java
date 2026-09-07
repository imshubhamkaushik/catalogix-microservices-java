package com.catalogix.cart.controller;

import com.catalogix.cart.dto.*;
import com.catalogix.cart.exception.WishlistItemNotFoundException;
import com.catalogix.security.JwtAuthFilter;
import com.catalogix.security.RateLimiterFilter;
import com.catalogix.cart.svc.WishlistSvc;
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
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
@WebMvcTest(
        controllers = WishlistController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class WishlistControllerTest {

    private static final String TOKEN = "Bearer token";

    @Autowired
    private JsonMapper mapper;

    @MockitoBean
    private WishlistSvc svc;

    @Autowired
    private MockMvc mvc;

    private WishlistItemResponse sampleResponse() {
        return new WishlistItemResponse(1L, "Phone", new BigDecimal("100.00"), 5, Instant.now());
    }

    @Test
    void listReturnsTheCurrentUsersWishlist() throws Exception {
        when(svc.list(42L, TOKEN)).thenReturn(List.of(sampleResponse()));

        mvc.perform(get("/wishlist").requestAttr("userId", 42L).requestAttr("bearerToken", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[0].productName").value("Phone"));
    }

    @Test
    @SuppressWarnings("null")
    void addReturnsCreated() throws Exception {
        when(svc.add(42L, 1L, TOKEN)).thenReturn(sampleResponse());

        AddWishlistItemRequest req = new AddWishlistItemRequest();
        req.setProductId(1L);

        mvc.perform(post("/wishlist")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(1));
    }

    @Test
    void addRejectsAMissingProductId() throws Exception {
        mvc.perform(post("/wishlist")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removeReturnsNoContent() throws Exception {
        mvc.perform(delete("/wishlist/1").requestAttr("userId", 42L).requestAttr("bearerToken", TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    void removeReturnsNotFoundWhenNotInWishlist() throws Exception {
        doThrow(new WishlistItemNotFoundException(1L)).when(svc).remove(42L, 1L);

        mvc.perform(delete("/wishlist/1").requestAttr("userId", 42L).requestAttr("bearerToken", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void moveToCartDefaultsToQuantityOneWithNoBody() throws Exception {
        mvc.perform(post("/wishlist/1/move-to-cart").requestAttr("userId", 42L).requestAttr("bearerToken", TOKEN))
                .andExpect(status().isNoContent());

        verify(svc).moveToCart(42L, 1L, 1, TOKEN);
    }

    @Test
    void moveToCartHonorsAnExplicitQuantity() throws Exception {
        MoveToCartRequest req = new MoveToCartRequest();
        req.setQuantity(3);

        mvc.perform(post("/wishlist/1/move-to-cart")
                .requestAttr("userId", 42L)
                .requestAttr("bearerToken", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(svc).moveToCart(42L, 1L, 3, TOKEN);
    }

    @Test
    void moveToCartReturnsNotFoundWhenNotInWishlist() throws Exception {
        doThrow(new WishlistItemNotFoundException(1L)).when(svc).moveToCart(42L, 1L, 1, TOKEN);

        mvc.perform(post("/wishlist/1/move-to-cart").requestAttr("userId", 42L).requestAttr("bearerToken", TOKEN))
                .andExpect(status().isNotFound());
    }
}
