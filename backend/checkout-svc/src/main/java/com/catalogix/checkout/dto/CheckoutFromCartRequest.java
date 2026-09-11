package com.catalogix.checkout.dto;

// POST /orders/checkout previously took no body at all. This is
// intentionally optional (the endpoint still works with no body / a null
// addressId, same as before) — see CreateOrderRequest.addressId for why.
public class CheckoutFromCartRequest {

    private Long addressId;

    public CheckoutFromCartRequest() {
        /*
        * Required by Jackson for JSON deserialization. The request body is optional,
        * so Spring may also construct this DTO when the endpoint is invoked without
        * an addressId.
        */
    }

    public Long getAddressId() { return addressId; }
    public void setAddressId(Long addressId) { this.addressId = addressId; }
}
