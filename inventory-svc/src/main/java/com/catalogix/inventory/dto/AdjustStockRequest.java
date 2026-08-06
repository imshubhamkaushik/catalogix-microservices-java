package com.catalogix.inventory.dto;

/**
 * delta is signed: negative reserves stock (checkout), positive restocks
 * (compensation / cancellation). Same convention as the original
 * product-svc adjustStock(id, delta) it was extracted from.
 */
public class AdjustStockRequest {
    private int delta;

    public int getDelta() { return delta; }
    public void setDelta(int delta) { this.delta = delta; }
}
