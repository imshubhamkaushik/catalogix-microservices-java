package com.catalogix.cart.exception;

public class WishlistItemNotFoundException extends RuntimeException {
    public WishlistItemNotFoundException(Long productId) {
        super("Product " + productId + " is not in your wishlist");
    }
}
