package com.catalogix.cart.svc;

import com.catalogix.cart.client.CatalogClient;
import com.catalogix.cart.client.InventoryClient;
import com.catalogix.cart.client.ProductInfo;
import com.catalogix.cart.dto.AddCartItemRequest;
import com.catalogix.cart.exception.WishlistItemNotFoundException;
import com.catalogix.cart.model.WishlistItem;
import com.catalogix.cart.repository.WishlistItemRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WishlistSvcTest {

    @Mock
    private WishlistItemRepository repo;

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private CartSvc cartSvc;

    private WishlistSvc svc;

    private static final String TOKEN = "Bearer test-token";
    private static final Long USER_ID = 42L;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        svc = new WishlistSvc(repo, catalogClient, inventoryClient, cartSvc);
    }

    private ProductInfo product(Long id, String name, String price) {
        ProductInfo p = new ProductInfo();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        return p;
    }

    // ---- list ----

    @Test
    void listMergesLiveProductAndStockInfoIntoEachEntry() {
        WishlistItem item = new WishlistItem(USER_ID, 1L);

        when(repo.findByUserIdOrderByAddedAtDesc(USER_ID))
                .thenReturn(List.of(item));

        when(catalogClient.fetch(1L, TOKEN))
                .thenReturn(product(1L, "Phone", "100.00"));

        when(inventoryClient.fetchQuantity(1L, TOKEN))
                .thenReturn(5);

        var result = svc.list(USER_ID, TOKEN);

        assertEquals(1, result.size());
        assertEquals("Phone", result.get(0).getProductName());
        assertEquals(5, result.get(0).getStockQuantity());

        verify(catalogClient).fetch(1L, TOKEN);
        verify(inventoryClient).fetchQuantity(1L, TOKEN);
    }

    // ---- add ----

    @Test
    void addSavesANewEntryAfterValidatingTheProductExists() {
        when(repo.findByUserIdAndProductId(USER_ID, 1L))
                .thenReturn(Optional.empty());

        when(catalogClient.fetch(1L, TOKEN))
                .thenReturn(product(1L, "Phone", "100.00"));

        when(repo.save(any(WishlistItem.class)))
                .thenAnswer(invocation -> {
                    WishlistItem saved = invocation.getArgument(0);
                    saved.setId(1L);
                    return saved;
                });

        when(inventoryClient.fetchQuantity(1L, TOKEN))
                .thenReturn(5);

        var result = svc.add(USER_ID, 1L, TOKEN);

        assertEquals(1L, result.getProductId());

        // Product information used to validate the product is reused while
        // building the response, so catalog-svc is called only once.
        verify(catalogClient).fetch(1L, TOKEN);

        verify(repo).save(any(WishlistItem.class));
        verify(inventoryClient).fetchQuantity(1L, TOKEN);
    }

    @Test
    void addIsIdempotentForAnAlreadySavedProduct() {
        WishlistItem existing = new WishlistItem(USER_ID, 1L);

        when(repo.findByUserIdAndProductId(USER_ID, 1L))
                .thenReturn(Optional.of(existing));

        when(catalogClient.fetch(1L, TOKEN))
                .thenReturn(product(1L, "Phone", "100.00"));

        when(inventoryClient.fetchQuantity(1L, TOKEN))
                .thenReturn(5);

        svc.add(USER_ID, 1L, TOKEN);

        verify(repo, never()).save(any());
        verify(catalogClient).fetch(1L, TOKEN);
        verify(inventoryClient).fetchQuantity(1L, TOKEN);
    }

    @Test
    void addRecoversGracefullyFromAConcurrentDuplicateInsert() {
        when(repo.findByUserIdAndProductId(USER_ID, 1L))
                .thenAnswer(new Answer<Optional<WishlistItem>>() {
                        private int invocationCount;

                        @Override
                        public Optional<WishlistItem> answer(InvocationOnMock invocation) {
                                invocationCount++;

                                return invocationCount == 1
                                        ? Optional.empty()
                                        : Optional.of(new WishlistItem(USER_ID, 1L));
                        }
                });

        when(catalogClient.fetch(1L, TOKEN))
                .thenReturn(product(1L, "Phone", "100.00"));

        when(inventoryClient.fetchQuantity(1L, TOKEN))
                .thenReturn(5);

        when(repo.save(any(WishlistItem.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        var result = svc.add(USER_ID, 1L, TOKEN);

        assertEquals(1L, result.getProductId());

        // The product was already fetched before the insert race occurred,
        // so the response path should reuse that ProductInfo.
        verify(catalogClient).fetch(1L, TOKEN);

        verify(inventoryClient).fetchQuantity(1L, TOKEN);
    }

    // ---- remove ----

    @Test
    void removeThrowsWhenTheProductIsNotInTheWishlist() {
        when(repo.findByUserIdAndProductId(USER_ID, 1L))
                .thenReturn(Optional.empty());

        assertThrows(
                WishlistItemNotFoundException.class,
                () -> svc.remove(USER_ID, 1L)
        );
    }

    @Test
    void removeDeletesTheEntry() {
        WishlistItem existing = new WishlistItem(USER_ID, 1L);

        when(repo.findByUserIdAndProductId(USER_ID, 1L))
                .thenReturn(Optional.of(existing));

        svc.remove(USER_ID, 1L);

        verify(repo).delete(existing);
    }

    // ---- moveToCart ----

    @Test
    void moveToCartAddsToCartThenRemovesFromWishlist() {
        WishlistItem existing = new WishlistItem(USER_ID, 1L);

        when(repo.findByUserIdAndProductId(USER_ID, 1L))
                .thenReturn(Optional.of(existing));

        svc.moveToCart(USER_ID, 1L, 2, TOKEN);

        ArgumentCaptor<AddCartItemRequest> captor =
                ArgumentCaptor.forClass(AddCartItemRequest.class);

        verify(cartSvc).addItem(
                eq(USER_ID),
                captor.capture(),
                eq(TOKEN)
        );

        assertEquals(1L, captor.getValue().getProductId());
        assertEquals(2, captor.getValue().getQuantity());

        verify(repo).delete(existing);
    }

    @Test
    void moveToCartThrowsWhenTheProductIsNotInTheWishlist() {
        when(repo.findByUserIdAndProductId(USER_ID, 1L))
                .thenReturn(Optional.empty());

        assertThrows(
                WishlistItemNotFoundException.class,
                () -> svc.moveToCart(USER_ID, 1L, 1, TOKEN)
        );

        verifyNoInteractions(cartSvc);
    }
}