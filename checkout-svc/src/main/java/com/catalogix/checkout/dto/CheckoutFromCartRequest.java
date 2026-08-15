package com.catalogix.checkout.dto;

// POST /orders/checkout previously took no body at all. This is
// intentionally optional (the endpoint still works with no body / a null
// addressId, same as before) — see CreateOrderRequest.addressId for why.
public class CheckoutFromCartRequest {

    private Long addressId;

    public CheckoutFromCartRequest() {}

    public Long getAddressId() { return addressId; }
    public void setAddressId(Long addressId) { this.addressId = addressId; }
}
