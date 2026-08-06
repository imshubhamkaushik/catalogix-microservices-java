package com.catalogix.inventory.exception;

public class StockItemNotFoundException extends RuntimeException {
    public StockItemNotFoundException(Long productId) {
        super("No stock record for product: " + productId);
    }
}
