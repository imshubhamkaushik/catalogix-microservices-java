package com.catalogix.checkout.dto;

import jakarta.validation.constraints.NotBlank;

// A mock payment request — no real card data is ever meaningfully validated
// or stored beyond the last 4 digits, purely cosmetic here (see payment-svc's PaymentSvc).
public class PayOrderRequest {

    @NotBlank(message = "method is required")
    private String method;

    private String cardLast4;

    public PayOrderRequest() {}

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getCardLast4() { return cardLast4; }
    public void setCardLast4(String cardLast4) { this.cardLast4 = cardLast4; }
}
