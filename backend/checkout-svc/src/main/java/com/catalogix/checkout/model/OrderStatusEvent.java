package com.catalogix.checkout.model;

import jakarta.persistence.*;
import java.time.Instant;

// One row per status transition an order actually goes through — the
// Amazon/Flipkart-style "Order Placed -> Payment Confirmed -> Shipped ->
// Delivered" timeline. Deliberately NOT a row for every payment *attempt*
// (a declined card doesn't move the order to a new OrderStatus, so it
// doesn't get a row here either) — this table mirrors real status
// transitions only, which is what a tracking view actually shows a customer.
@Entity
@Table(name = "order_status_events")
public class OrderStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    // Short human-readable context, e.g. "Payment confirmed", "Cancelled by customer".
    @Column(length = 200)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public OrderStatusEvent() {
    }

    public OrderStatusEvent(OrderStatus status, String note) {
        this.status = status;
        this.note = note;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
