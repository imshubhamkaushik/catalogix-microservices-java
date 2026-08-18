package com.catalogix.checkout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class RequestReturnRequest {

    @NotBlank(message = "reason is required")
    @Size(max = 500, message = "reason must be at most 500 characters")
    private String reason;

    @NotEmpty(message = "at least one item is required")
    @Valid
    private List<ReturnItemRequest> items;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public List<ReturnItemRequest> getItems() { return items; }
    public void setItems(List<ReturnItemRequest> items) { this.items = items; }
}
