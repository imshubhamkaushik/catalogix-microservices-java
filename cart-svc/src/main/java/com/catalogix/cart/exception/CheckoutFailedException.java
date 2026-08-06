package com.catalogix.cart.exception;

// Wraps a non-2xx response from checkout-svc so the controller can forward
// the same status code and body back to the browser, instead of it looking
// like a cart-svc-internal 500.
public class CheckoutFailedException extends RuntimeException {
    private final int statusCode;
    private final String body;

    public CheckoutFailedException(int statusCode, String body) {
        super("checkout-svc returned " + statusCode);
        this.statusCode = statusCode;
        this.body = body;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
}
