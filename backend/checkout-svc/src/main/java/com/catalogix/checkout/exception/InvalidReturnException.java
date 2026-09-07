package com.catalogix.checkout.exception;

// Covers every "this return request doesn't make sense" case: order not
// yet delivered, past the return window, quantity exceeds what's left to
// return, or acting on a return that's already been decided.
public class InvalidReturnException extends RuntimeException {
    public InvalidReturnException(String message) {
        super(message);
    }
}
