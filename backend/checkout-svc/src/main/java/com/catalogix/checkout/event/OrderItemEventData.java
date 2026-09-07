package com.catalogix.checkout.event;

import java.math.BigDecimal;

// Plain data carried inside order events — deliberately not the same class as
// OrderItemResponse (the REST DTO); this is the wire contract for the message
// queue and is free to evolve independently of the HTTP API.
public record OrderItemEventData(String productName, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {
}
