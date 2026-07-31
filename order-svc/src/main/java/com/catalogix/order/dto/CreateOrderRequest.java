package com.catalogix.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class CreateOrderRequest {

    @NotEmpty(message = "An order must contain at least one item")
    @Valid
    private List<OrderItemRequest> items;

    public CreateOrderRequest() {
    }

    public List<OrderItemRequest> getItems() {
        return items;
    }
    public void setItems(List<OrderItemRequest> items) {
        this.items = items;
    }
}
