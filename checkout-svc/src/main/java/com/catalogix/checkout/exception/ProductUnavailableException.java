package com.catalogix.checkout.exception;

// Raised when product-svc can't fulfil a line item: the product doesn't
// exist, or there isn't enough stock.
public class ProductUnavailableException extends RuntimeException {
    public ProductUnavailableException(String message) {
        super(message);
    }
}
