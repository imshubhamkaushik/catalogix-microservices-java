package com.catalogix.inventory.svc;

import com.catalogix.inventory.dto.StockResponse;
import com.catalogix.inventory.exception.InsufficientStockException;
import com.catalogix.inventory.exception.StockItemNotFoundException;
import com.catalogix.inventory.model.StockItem;
import com.catalogix.inventory.repository.StockItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventorySvc {

    private final StockItemRepository repo;

    public InventorySvc(StockItemRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public StockResponse get(Long productId) {
        StockItem item = repo.findById(productId)
                .orElseThrow(() -> new StockItemNotFoundException(productId));
        return new StockResponse(item.getProductId(), item.getQuantity());
    }

    @Transactional
    public StockResponse init(Long productId, int initialQuantity) {
        if (repo.existsById(productId)) {
            // Idempotent: catalog-svc may retry product creation's follow-up
            // call after a timeout without knowing whether it landed.
            return get(productId);
        }
        StockItem saved = repo.save(new StockItem(productId, initialQuantity));
        return new StockResponse(saved.getProductId(), saved.getQuantity());
    }

    /**
     * delta negative = reserve (checkout), positive = restock (compensation).
     * Pessimistic row lock makes the read-check-write atomic against
     * concurrent adjustments for the same product — this is the exact
     * protection the original review found coupon redemption was missing;
     * stock has always had it and still does here.
     */
    @Transactional
    public StockResponse adjust(Long productId, int delta) {
        StockItem item = repo.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new StockItemNotFoundException(productId));

        int newQuantity = item.getQuantity() + delta;
        if (newQuantity < 0) {
            throw new InsufficientStockException(productId, item.getQuantity(), -delta);
        }
        item.setQuantity(newQuantity);
        StockItem saved = repo.save(item);
        return new StockResponse(saved.getProductId(), saved.getQuantity());
    }
}
