package com.catalogix.inventory.svc;

import com.catalogix.inventory.dto.InventoryResponse;
import com.catalogix.inventory.exception.InsufficientInventoryException;
import com.catalogix.inventory.exception.InventoryItemNotFoundException;
import com.catalogix.inventory.model.InventoryItem;
import com.catalogix.inventory.repository.InventoryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventorySvc {

    private final InventoryItemRepository repo;

    public InventorySvc(InventoryItemRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public InventoryResponse get(Long productId) {
        InventoryItem item = repo.findById(productId)
                .orElseThrow(() -> new InventoryItemNotFoundException(productId));
        return new InventoryResponse(item.getProductId(), item.getQuantity());
    }

    @Transactional
    public InventoryResponse init(Long productId, int initialQuantity) {
        if (repo.existsById(productId)) {
            // Idempotent: catalog-svc may retry product creation's follow-up
            // call after a timeout without knowing whether it landed.
            return get(productId);
        }
        InventoryItem saved = repo.save(new InventoryItem(productId, initialQuantity));
        return new InventoryResponse(saved.getProductId(), saved.getQuantity());
    }

    /**
     * delta negative = reserve (checkout), positive = restock (compensation).
     * Pessimistic row lock makes the read-check-write atomic against
     * concurrent adjustments for the same product — this is the exact
     * protection the original review found coupon redemption was missing;
     * stock has always had it and still does here.
     */
    @Transactional
    public InventoryResponse adjust(Long productId, int delta) {
        InventoryItem item = repo.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new InventoryItemNotFoundException(productId));

        int newQuantity = item.getQuantity() + delta;
        if (newQuantity < 0) {
            throw new InsufficientInventoryException(productId, item.getQuantity(), -delta);
        }
        item.setQuantity(newQuantity);
        InventoryItem saved = repo.save(item);
        return new InventoryResponse(saved.getProductId(), saved.getQuantity());
    }
}
