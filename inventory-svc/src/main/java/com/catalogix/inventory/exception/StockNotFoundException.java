package com.catalogix.inventory.exception;

public class StockNotFoundException extends RuntimeException {
    public StockNotFoundException(Long productId) {
        super("No inventory record for product " + productId);
    }
}
