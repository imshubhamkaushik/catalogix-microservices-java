package com.catalogix.checkout.exception;

public class ReturnRequestNotFoundException extends RuntimeException {
    public ReturnRequestNotFoundException(Long id) {
        super("Return request " + id + " not found");
    }
}
