package com.catalogix.cart.dto;

import java.util.List;

// What cart-svc sends to checkout-svc's POST /orders to convert this cart
// into an order. checkout-svc does the actual stock reservation, coupon
// redemption, and pricing snapshot — cart-svc only forwards what's in it.
public class CheckoutRequestForward {
    private List<Item> items;
    private String couponCode;
    private String idempotencyKey;

    public static class Item {
        private Long productId;
        private Integer quantity;

        public Item() {}
        public Item(Long productId, Integer quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
