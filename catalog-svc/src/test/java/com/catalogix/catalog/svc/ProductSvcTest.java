package com.catalogix.catalog.svc;

import com.catalogix.catalog.client.InventoryClient;
import com.catalogix.catalog.dto.CreateProductRequest;
import com.catalogix.catalog.dto.PagedResponse;
import com.catalogix.catalog.dto.ProductResponse;
import com.catalogix.catalog.exception.ForbiddenException;
import com.catalogix.catalog.exception.ProductNotFoundException;
import com.catalogix.catalog.model.Product;
import com.catalogix.catalog.repository.ProductRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProductSvcTest {

    @Mock private ProductRepository repo;
    @Mock private InventoryClient inventoryClient;

    private ProductSvc svc;

    private static final String TOKEN = "Bearer test-token";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new ProductSvc(repo, inventoryClient);
    }

    private Product product(Long id, String name, String price, String category, Long ownerId) {
        Product p = new Product(name, "A " + name.toLowerCase(), new BigDecimal(price), category, ownerId);
        p.setId(id);
        return p;
    }

    // ---- search ----

    @Test
    void searchMergesLiveStockIntoEachResult() {
        Pageable pageable = PageRequest.of(0, 20);
        Product p = product(1L, "Phone", "100.00", "ELECTRONICS", 42L);
        when(repo.search(null, null, pageable)).thenReturn(new PageImpl<>(List.of(p), pageable, 1));
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(7);

        PagedResponse<ProductResponse> result = svc.search(null, null, pageable, TOKEN);

        assertEquals(1, result.getContent().size());
        assertEquals(7, result.getContent().get(0).getStockQuantity());
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void searchTrimsBlankFiltersToNull() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repo.search(null, null, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        svc.search("   ", "  ", pageable, TOKEN);

        verify(repo).search(null, null, pageable);
    }

    @Test
    void searchPassesThroughTrimmedFilters() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repo.search("phone", "electronics", pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        svc.search(" phone ", " electronics ", pageable, TOKEN);

        verify(repo).search("phone", "electronics", pageable);
    }

    // ---- create ----

    @Test
    @SuppressWarnings("null")
    void createInitializesStockAndReturnsIt() {
        when(repo.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        CreateProductRequest req = new CreateProductRequest();
        req.setName("Phone");
        req.setPrice(new BigDecimal("100.00"));
        req.setStockQuantity(10);

        ProductResponse resp = svc.create(req, 42L, TOKEN);

        assertEquals(1L, resp.getId());
        assertEquals(10, resp.getStockQuantity());
        assertEquals(42L, resp.getOwnerId());
        verify(inventoryClient).init(1L, 10, TOKEN);
    }

    @Test
    @SuppressWarnings("null")
    void createDefaultsCategoryToGeneralWhenBlank() {
        when(repo.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateProductRequest req = new CreateProductRequest();
        req.setName("Phone");
        req.setPrice(new BigDecimal("100.00"));

        svc.create(req, 42L, TOKEN);

        verify(repo).save(argThat((Product p) -> "GENERAL".equals(p.getCategory())));
    }

    @Test
    @SuppressWarnings("null")
    void createDefaultsStockQuantityToZeroWhenOmitted() {
        when(repo.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        CreateProductRequest req = new CreateProductRequest();
        req.setName("Phone");
        req.setPrice(new BigDecimal("100.00"));

        ProductResponse resp = svc.create(req, 42L, TOKEN);

        assertEquals(0, resp.getStockQuantity());
        verify(inventoryClient).init(1L, 0, TOKEN);
    }

    // ---- findById ----

    @Test
    void findByIdReturnsProductWithLiveStock() {
        when(repo.findById(1L)).thenReturn(Optional.of(product(1L, "Phone", "100.00", "ELECTRONICS", 42L)));
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(7);

        Optional<ProductResponse> resp = svc.findById(1L, TOKEN);

        assertTrue(resp.isPresent());
        assertEquals(7, resp.get().getStockQuantity());
    }

    @Test
    void findByIdReturnsEmptyWhenNotFound() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertTrue(svc.findById(99L, TOKEN).isEmpty());
        verifyNoInteractions(inventoryClient);
    }

    // ---- deleteById ----

    @Test
    void deleteByIdAllowsOwner() {
        when(repo.findById(1L)).thenReturn(Optional.of(product(1L, "Phone", "100.00", "GENERAL", 42L)));

        assertTrue(svc.deleteById(1L, 42L, "USER"));
        verify(repo).deleteById(1L);
    }

    @Test
    void deleteByIdAllowsAdminEvenWhenNotOwner() {
        when(repo.findById(1L)).thenReturn(Optional.of(product(1L, "Phone", "100.00", "GENERAL", 42L)));

        assertTrue(svc.deleteById(1L, 7L, "ADMIN"));
        verify(repo).deleteById(1L);
    }

    @Test
    void deleteByIdRejectsNonOwnerNonAdmin() {
        when(repo.findById(1L)).thenReturn(Optional.of(product(1L, "Phone", "100.00", "GENERAL", 42L)));

        assertThrows(ForbiddenException.class, () -> svc.deleteById(1L, 7L, "USER"));
        verify(repo, never()).deleteById(anyLong());
    }

    @Test
    void deleteByIdReturnsFalseWhenProductDoesNotExist() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertFalse(svc.deleteById(99L, 42L, "USER"));
        verify(repo, never()).deleteById(anyLong());
    }

    // ---- adjustStock ----

    @Test
    void adjustStockThrowsWhenProductDoesNotExist() {
        when(repo.existsById(99L)).thenReturn(false);

        assertThrows(ProductNotFoundException.class, () -> svc.adjustStock(99L, 5, TOKEN));
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void adjustStockMergesTheNewQuantityIntoTheResponse() {
        when(repo.existsById(1L)).thenReturn(true);
        when(inventoryClient.adjust(1L, 5, TOKEN)).thenReturn(15);
        when(repo.findById(1L)).thenReturn(Optional.of(product(1L, "Phone", "100.00", "GENERAL", 42L)));
        // findById's own internal fetchQuantity call — the value adjustStock
        // then overwrites with the freshly-adjusted quantity from adjust().
        when(inventoryClient.fetchQuantity(1L, TOKEN)).thenReturn(999);

        ProductResponse resp = svc.adjustStock(1L, 5, TOKEN);

        assertEquals(15, resp.getStockQuantity());
    }
}
