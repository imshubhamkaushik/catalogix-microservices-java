package com.catalogix.inventory.svc;

import com.catalogix.inventory.dto.StockResponse;
import com.catalogix.inventory.exception.InsufficientStockException;
import com.catalogix.inventory.exception.StockItemNotFoundException;
import com.catalogix.inventory.model.StockItem;
import com.catalogix.inventory.repository.StockItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventorySvcTest {

    private StockItemRepository repo;
    private InventorySvc svc;

    @BeforeEach
    void setUp() {
        repo = mock(StockItemRepository.class);
        svc = new InventorySvc(repo);
    }

    @Test
    void adjustIncreasesQuantityOnRestock() {

        StockItem item = new StockItem(1L, 5);

        when(repo.findByProductIdForUpdate(1L))
                .thenReturn(Optional.of(item));

        when(repo.save(any(StockItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        StockResponse response = svc.adjust(1L, 10);

        assertThat(response.getQuantity()).isEqualTo(15);
    }

    @Test
    void adjustThrowsWhenInsufficientStock() {

        StockItem item = new StockItem(1L, 3);

        when(repo.findByProductIdForUpdate(1L))
                .thenReturn(Optional.of(item));

        assertThatThrownBy(() -> svc.adjust(1L, -5))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void adjustThrowsWhenProductDoesNotExist() {

        when(repo.findByProductIdForUpdate(99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.adjust(99L, -1))
                .isInstanceOf(StockItemNotFoundException.class);
    }

    @Test
    void initReturnsExistingItemIfAlreadyPresent() {

        StockItem item = new StockItem(1L, 10);

        when(repo.existsById(1L)).thenReturn(true);
        when(repo.findById(1L)).thenReturn(Optional.of(item));

        StockResponse response = svc.init(1L, 20);

        assertThat(response.getProductId()).isEqualTo(1L);
        assertThat(response.getQuantity()).isEqualTo(10);
    }

    @Test
    void initCreatesNewItemIfMissing() {

        StockItem item = new StockItem(1L, 10);

        when(repo.existsById(1L)).thenReturn(false);
        when(repo.save(any(StockItem.class)))
                .thenReturn(item);

        StockResponse response = svc.init(1L, 10);

        assertThat(response.getProductId()).isEqualTo(1L);
        assertThat(response.getQuantity()).isEqualTo(10);
    }
}