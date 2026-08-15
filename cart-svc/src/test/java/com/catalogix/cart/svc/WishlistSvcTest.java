package com.catalogix.cart.svc;

import com.catalogix.cart.client.CatalogClient;
import com.catalogix.cart.client.InventoryClient;
import com.catalogix.cart.client.ProductInfo;
import com.catalogix.cart.dto.AddCartItemRequest;
import com.catalogix.cart.exception.WishlistItemNotFoundException;
import com.catalogix.cart.model.WishlistItem;
import com.catalogix.cart.repository.WishlistItemRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WishlistSvcTest {

    @Mock private WishlistItemRepository repo;
    @Mock private CatalogClient catalogClient;
    @Mock private InventoryClient inventoryClient;
    @Mock private CartSvc cartSvc;

    private WishlistSvc svc;

    private static final String TOKEN = "Bearer test-token";
    private static final Long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new WishlistSvc(repo, catalogClient, inventoryClient, cartSvc);
    }

    private ProductInfo product(Long id, String name, String price) {
        ProductInfo p = new ProductInfo();
        p.setId(id); p.setName(name); p.setPrice(new BigDecimal(price));
        return p;
    }

    // ---- list ----

    @Test
    void listMergesLiveProductAndStockInfoIntoEachEntry() {
        WishlistItem item = new WishlistItem(USER_ID, 1L);
        when(repo.findByUserIdOrderByAddedAtDesc(USER_ID)).thenReturn(List.of(item));
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(5);

        var result = svc.list(USER_ID, TOKEN);

        assertEquals(1, result.size());
        assertEquals("Phone", result.get(0).getProductName());
        assertEquals(5, result.get(0).getStockQuantity());
    }

    // ---- add ----

    @Test
    void addSavesANewEntryAfterValidatingTheProductExists() {
        when(repo.findByUserIdAndProductId(USER_ID, 1L)).thenReturn(Optional.empty());
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(repo.save(any(WishlistItem.class))).thenAnswer(inv -> {
            WishlistItem saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(5);

        var result = svc.add(USER_ID, 1L, TOKEN);

        assertEquals(1L, result.getProductId());
        verify(catalogClient).fetch(1L, TOKEN);
    }

    @Test
    void addIsIdempotentForAnAlreadySavedProduct() {
        WishlistItem existing = new WishlistItem(USER_ID, 1L);
        when(repo.findByUserIdAndProductId(USER_ID, 1L)).thenReturn(Optional.of(existing));
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(5);

        svc.add(USER_ID, 1L, TOKEN);

        verify(repo, never()).save(any());
    }

    // Added alongside the DB unique constraint (see V2 migration): two
    // concurrent "add to wishlist" taps for the same product must both
    // succeed from the caller's point of view, not surface the losing
    // side's constraint violation as a raw error.
    @Test
    void addRecoversGracefullyFromAConcurrentDuplicateInsert() {
        when(repo.findByUserIdAndProductId(USER_ID, 1L))
                .thenReturn(Optional.empty())              // first check: not there yet
                .thenReturn(Optional.of(new WishlistItem(USER_ID, 1L))); // after losing the race
        when(catalogClient.fetch(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00"));
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(5);
        when(repo.save(any(WishlistItem.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        var result = svc.add(USER_ID, 1L, TOKEN);

        assertEquals(1L, result.getProductId());
    }

    // ---- remove ----

    @Test
    void removeThrowsWhenTheProductIsNotInTheWishlist() {
        when(repo.findByUserIdAndProductId(USER_ID, 1L)).thenReturn(Optional.empty());

        assertThrows(WishlistItemNotFoundException.class, () -> svc.remove(USER_ID, 1L));
    }

    @Test
    void removeDeletesTheEntry() {
        WishlistItem existing = new WishlistItem(USER_ID, 1L);
        when(repo.findByUserIdAndProductId(USER_ID, 1L)).thenReturn(Optional.of(existing));

        svc.remove(USER_ID, 1L);

        verify(repo).delete(existing);
    }

    // ---- moveToCart ----

    @Test
    void moveToCartAddsToCartThenRemovesFromWishlist() {
        WishlistItem existing = new WishlistItem(USER_ID, 1L);
        when(repo.findByUserIdAndProductId(USER_ID, 1L)).thenReturn(Optional.of(existing));

        svc.moveToCart(USER_ID, 1L, 2, TOKEN);

        var captor = org.mockito.ArgumentCaptor.forClass(AddCartItemRequest.class);
        verify(cartSvc).addItem(eq(USER_ID), captor.capture(), eq(TOKEN));
        assertEquals(1L, captor.getValue().getProductId());
        assertEquals(2, captor.getValue().getQuantity());
        verify(repo).delete(existing);
    }

    @Test
    void moveToCartThrowsWhenTheProductIsNotInTheWishlist() {
        when(repo.findByUserIdAndProductId(USER_ID, 1L)).thenReturn(Optional.empty());

        assertThrows(WishlistItemNotFoundException.class, () -> svc.moveToCart(USER_ID, 1L, 1, TOKEN));
        verifyNoInteractions(cartSvc);
    }
}
