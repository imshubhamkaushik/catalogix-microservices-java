package com.catalogix.checkout.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Id of the user (from user-svc) who placed this order.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // Optional client-supplied key (from the Idempotency-Key header). A repeated
    // request with the same key for the same user returns the original order
    // instead of creating (and double-charging stock for) a duplicate.
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "applied_coupon_code", length = 50)
    private String appliedCouponCode;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderItem> items = new ArrayList<>();

    // Status timeline — see OrderStatusEvent. LAZY: only the dedicated
    // tracking endpoint needs this, unlike items above (which every order
    // response includes), so ordinary list/get calls don't pay for the join.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderStatusEvent> statusEvents = new ArrayList<>();

    // Shipping address is a SNAPSHOT taken from user-svc's address book at
    // the moment the order was placed (see CheckoutSvc#placeOrder /
    // AddressClient) — not a live foreign key. Editing or deleting that
    // address afterwards must never change what an already-placed order
    // shows as its delivery address, same as Amazon/Flipkart's own "address
    // used for this order" behavior. All nullable: orders placed without an
    // addressId (or before this feature existed) simply have no shipping
    // snapshot.
    @Column(name = "shipping_label", length = 40)
    private String shippingLabel;

    @Column(name = "shipping_line1", length = 200)
    private String shippingLine1;

    @Column(name = "shipping_line2", length = 200)
    private String shippingLine2;

    @Column(name = "shipping_city", length = 100)
    private String shippingCity;

    @Column(name = "shipping_state", length = 100)
    private String shippingState;

    @Column(name = "shipping_pincode", length = 12)
    private String shippingPincode;

    @Column(name = "shipping_phone", length = 20)
    private String shippingPhone;

    // Set once, in payOrder(), the moment payment succeeds (or COD is
    // confirmed) — never touched again. This is what a later return/refund
    // request (see ReturnSvc) uses to decide HOW to reverse this order:
    // CARD/UPI route through payment-svc's refund endpoint using
    // paymentReference; COD never captured anything, so a return on a COD
    // order skips payment-svc entirely — there's nothing to refund.
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    // Snapshotted at the same moment as paymentMethod/paymentReference
    // (payOrder already has the caller's email in scope for the
    // order-confirmed notification — reusing it here costs nothing extra).
    // Exists for one purpose: the invoice endpoint (see CheckoutSvc#getInvoice)
    // needs a customer identity to print, and Order otherwise only ever
    // stores a bare userId.
    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    public Order() {
        // Required by JPA for entity instantiation.
    }

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public OrderStatus getStatus() {
        return status;
    }
    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }
    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getAppliedCouponCode() {
        return appliedCouponCode;
    }
    public void setAppliedCouponCode(String appliedCouponCode) {
        this.appliedCouponCode = appliedCouponCode;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }
    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    // Convenience method to keep both sides of the bidirectional association in sync.
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public List<OrderStatusEvent> getStatusEvents() {
        return statusEvents;
    }
    public void setStatusEvents(List<OrderStatusEvent> statusEvents) {
        this.statusEvents = statusEvents;
    }

    // Convenience method mirroring addItem — appends a timeline entry and
    // keeps both sides of the association in sync in one call.
    public void addStatusEvent(OrderStatus status, String note) {
        OrderStatusEvent event = new OrderStatusEvent(status, note);
        statusEvents.add(event);
        event.setOrder(this);
    }

    public String getShippingLabel() { return shippingLabel; }
    public void setShippingLabel(String shippingLabel) { this.shippingLabel = shippingLabel; }

    public String getShippingLine1() { return shippingLine1; }
    public void setShippingLine1(String shippingLine1) { this.shippingLine1 = shippingLine1; }

    public String getShippingLine2() { return shippingLine2; }
    public void setShippingLine2(String shippingLine2) { this.shippingLine2 = shippingLine2; }

    public String getShippingCity() { return shippingCity; }
    public void setShippingCity(String shippingCity) { this.shippingCity = shippingCity; }

    public String getShippingState() { return shippingState; }
    public void setShippingState(String shippingState) { this.shippingState = shippingState; }

    public String getShippingPincode() { return shippingPincode; }
    public void setShippingPincode(String shippingPincode) { this.shippingPincode = shippingPincode; }

    public String getShippingPhone() { return shippingPhone; }
    public void setShippingPhone(String shippingPhone) { this.shippingPhone = shippingPhone; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
}
