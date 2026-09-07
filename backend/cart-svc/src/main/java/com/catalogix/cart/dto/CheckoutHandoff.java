package com.catalogix.cart.dto;

import java.util.List;

public class CheckoutHandoff {
    private List<CartItemLine> items;
    private String couponCode;

    public CheckoutHandoff() {}
    public CheckoutHandoff(List<CartItemLine> items, String couponCode) {
        this.items = items;
        this.couponCode = couponCode;
    }
    public List<CartItemLine> getItems() { return items; }
    public void setItems(List<CartItemLine> items) { this.items = items; }
    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
}
