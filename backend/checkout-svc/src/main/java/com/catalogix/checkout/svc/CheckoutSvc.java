package com.catalogix.checkout.svc;

import com.catalogix.checkout.client.AddressClient;
import com.catalogix.checkout.client.CartClient;
import com.catalogix.checkout.client.CatalogClient;
import com.catalogix.checkout.client.CheckoutClients;
import com.catalogix.checkout.client.PaymentClient;
import com.catalogix.checkout.client.PromotionsClient;
import com.catalogix.checkout.dto.CreateOrderRequest;
import com.catalogix.checkout.dto.InvoiceLineResponse;
import com.catalogix.checkout.dto.InvoiceResponse;
import com.catalogix.checkout.dto.OrderItemResponse;
import com.catalogix.checkout.dto.OrderResponse;
import com.catalogix.checkout.dto.OrderTrackingResponse;
import com.catalogix.checkout.dto.PagedResponse;
import com.catalogix.checkout.dto.PayOrderRequest;
import com.catalogix.checkout.dto.ShippingAddressSummary;
import com.catalogix.checkout.dto.TrackingEventResponse;
import com.catalogix.checkout.event.OrderCancelledEvent;
import com.catalogix.checkout.event.OrderConfirmedEvent;
import com.catalogix.checkout.event.OrderItemEventData;
import com.catalogix.checkout.exception.CouponInvalidException;
import com.catalogix.checkout.exception.ForbiddenException;
import com.catalogix.checkout.exception.InvalidOrderStateException;
import com.catalogix.checkout.exception.OrderNotFoundException;
import com.catalogix.checkout.exception.RefundFailedException;
import com.catalogix.checkout.model.CompensationOutbox;
import com.catalogix.checkout.model.Order;
import com.catalogix.checkout.model.OrderItem;
import com.catalogix.checkout.model.OrderStatus;
import com.catalogix.checkout.model.OrderStatusEvent;
import com.catalogix.checkout.model.PaymentMethod;
import com.catalogix.checkout.repository.OrderRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The saga orchestrator for placing, paying for, shipping, delivering, and
 * cancelling orders. This is what used to be OrderSvc's in-process calls to
 * CartSvc/CouponSvc/PaymentSvc/ProductSvc — now four separate services
 * (cart-svc, promotions-svc, payment-svc, catalog-svc/inventory-svc), each
 * reached over HTTP, none of which shares a database transaction with this one
 * anymore. Orchestration (this class), not choreography, on purpose: the
 * compensation logic below is complex enough that it's worth having it all in
 * one readable place rather than scattered across five services' event
 * handlers.
 *
 * Saga shape for creating an order: 1. reserve stock for each item
 * (inventory-svc) -- compensable 2. commit coupon redemption, if any
 * (promotions-svc) -- compensable 3. persist the order (local DB write, this
 * service's own table) If ANY step fails — including step 3 itself, e.g. the
 * concurrent idempotency-key race the controller recovers from — every side
 * effect already committed in steps 1-2 is compensated. This closes a real gap
 * the original single-service version had: its try/catch only wrapped steps
 * 1-2, so a failure in step 3 (the DB save) left reserved stock and a redeemed
 * coupon permanently orphaned with nothing to show for them. See compensate().
 *
 * Compensation itself is attempted live first; if the live call also fails,
 * it's queued to compensation_outbox instead of just being logged and dropped —
 * see CompensationOutboxProcessor for the retry loop.
 *
 * TRANSACTION BOUNDARIES. The order-placement and payment methods below are
 * deliberately NOT @Transactional as a whole. They make several HTTP calls (each
 * up to ~11s: 3s connect + 8s read), and a method-wide transaction would hold a
 * pooled JDBC connection — 5 per instance in this service — and, for payment, a
 * row lock, across all of them: five slow checkouts exhaust the pool and every
 * other request fails after Hikari's 3s connection timeout. Instead each DB
 * step runs in its own short transaction (see the TransactionOperations field)
 * and remote calls happen between them with no connection held.
 * cancelOrder / expireUnpaidOrder / ReturnSvc still call other services inside a
 * transaction: their compensation intent must commit atomically with the state
 * change (transactional outbox), which needs a larger redesign than this one.
 */
@Service
public class CheckoutSvc {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSvc.class);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_STATUS_TRANSITIONS = Map.of(OrderStatus.CONFIRMED,
            Set.of(OrderStatus.SHIPPED), OrderStatus.SHIPPED, Set.of(OrderStatus.DELIVERED));

    private final OrderRepository repo;
    private final CompensationOutboxWriter outboxWriter;
    private final CheckoutClients clients;
    private final ApplicationEventPublisher eventPublisher;
    // Runs a DB step in its own short transaction (in production a TransactionTemplate).
    private final TransactionOperations tx;

    /**
     * Convenience constructor for unit tests: every "transaction" is just a direct call, so
     * mock-based tests exercise the same code path without a database.
     */
    public CheckoutSvc(OrderRepository repo, CompensationOutboxWriter outboxWriter, CheckoutClients clients,
            ApplicationEventPublisher eventPublisher) {
        this(repo, outboxWriter, clients, eventPublisher, TransactionOperations.withoutTransaction());
    }

    @Autowired
    public CheckoutSvc(OrderRepository repo, CompensationOutboxWriter outboxWriter, CheckoutClients clients,
            ApplicationEventPublisher eventPublisher, TransactionOperations tx) {
        this.repo = repo;
        this.outboxWriter = outboxWriter;
        this.clients = clients;
        this.eventPublisher = eventPublisher;
        this.tx = tx;
    }

    // reserveOperationId: the idempotency id sent to inventory-svc when this line
    // was reserved,
    // or null when the reservation belongs to an already-persisted order (then the
    // release is
    // keyed by the order instead — see compensateWithReason).
    private record ReservedItem(Long productId, Long sellerId, String productName, BigDecimal price, int quantity,
            String reserveOperationId) {
    }

    public record OrderCreationResult(OrderResponse order, boolean wasNew) {
    }

    public record OrderPaymentResult(OrderResponse order, boolean paymentSucceeded) {
    }

    // ---- Direct API: caller supplies items explicitly, no cart involved ----
    // Not @Transactional: see "TRANSACTION BOUNDARIES" in the class comment.
    public OrderCreationResult createOrder(Long userId, CreateOrderRequest req, String bearerToken,
            String idempotencyKey) {
        idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        Optional<OrderCreationResult> existing = existingOrder(userId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<CartClient.ItemLine> lines = req.getItems().stream()
                .map(i -> new CartClient.ItemLine(i.getProductId(), i.getQuantity())).toList();

        return placeOrder(userId, lines, req.getCouponCode(), req.getAddressId(), bearerToken, idempotencyKey);
    }

    // ---- Cart-driven checkout: pulls the cart's contents from cart-svc,
    // places the order the same way, then clears the cart. ----
    // Not @Transactional: see "TRANSACTION BOUNDARIES" in the class comment.
    public OrderCreationResult checkoutFromCart(Long userId, Long addressId, String bearerToken,
            String idempotencyKey) {
        idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        Optional<OrderCreationResult> existing = existingOrder(userId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        CartClient.Handoff handoff = clients.cart().handoff(bearerToken);
        if (handoff == null) {
            throw new IllegalStateException("cart-svc returned a null checkout handoff");
        }

        OrderCreationResult result = placeOrder(userId, handoff.items(), handoff.couponCode(), addressId, bearerToken,
                idempotencyKey);

        if (result.wasNew()) {
            try {
                clients.cart().clear(bearerToken);
            } catch (RuntimeException e) {
                log.warn("Order {} placed but clearing the cart failed: {}", result.order().getId(), e.getMessage());
            }
        }
        return result;
    }

    private Optional<OrderCreationResult> existingOrder(Long userId, String idempotencyKey) {
        idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        final String key = idempotencyKey;
        // Short read-only step; mapped to a DTO inside it so nothing lazy escapes the transaction.
        return tx.execute(status -> repo.findByUserIdAndIdempotencyKey(userId, key)
                .map(order -> new OrderCreationResult(toResponse(order), false)));
    }

    private OrderCreationResult placeOrder(Long userId, List<CartClient.ItemLine> items, String couponCode,
            Long addressId, String bearerToken, String idempotencyKey) {

        List<ReservedItem> reserved = new ArrayList<>();
        String committedCouponCode = null;
        String couponOperationId = null;
        // Groups every stock operation of THIS attempt. Reserve and release calls carry
        // ids
        // derived from it, so inventory-svc can recognise repeats and reverse exactly
        // what it applied.
        String reservationId = sagaId(userId, idempotencyKey);

        try {
            BigDecimal subtotal = BigDecimal.ZERO;
            int lineIndex = 0;
            for (CartClient.ItemLine line : items) {
                CatalogClient.ProductDto product = clients.catalog().fetch(line.productId(), bearerToken);
                String reserveOp = reservationId + ":reserve:" + lineIndex++;
                ReservedItem item = new ReservedItem(product.id(), product.ownerId(), product.name(), product.price(),
                        line.quantity(), reserveOp);
                // Registered BEFORE the call, on purpose. If the call times out we cannot know
                // whether inventory-svc applied it; only registered lines are compensated, so
                // registering afterwards leaked stock in exactly that case. Releasing a
                // reservation that never happened is harmless — the release names this
                // reservation (undoOf) and inventory-svc adds nothing back for one it never
                // saw.
                reserved.add(item);
                clients.inventory().adjust(line.productId(), -line.quantity(), reserveOp, null);
                subtotal = subtotal.add(product.price().multiply(BigDecimal.valueOf(line.quantity())));
            }

            BigDecimal discount = BigDecimal.ZERO;
            if (couponCode != null && !couponCode.isBlank()) {
                couponOperationId = "coupon:commit:" + reservationId;
                PromotionsClient.DiscountDto d = clients.promotions().commit(couponCode, subtotal,
                        couponOperationId);
                discount = d.discountAmount();
                committedCouponCode = d.code();
            }

            Order order = new Order();
            order.setUserId(userId);
            order.setStatus(OrderStatus.PENDING_PAYMENT);
            order.setIdempotencyKey(idempotencyKey);
            for (ReservedItem r : reserved) {
                OrderItem orderItem = new OrderItem(r.productId(), r.productName(), r.quantity(), r.price());
                orderItem.setSellerId(r.sellerId());
                order.addItem(orderItem);
            }
            if (committedCouponCode != null) {
                order.setAppliedCouponCode(committedCouponCode);
                order.setCouponOperationId(couponOperationId);
                order.setDiscountAmount(discount);
            }
            order.setTotalAmount(subtotal.subtract(discount));

            if (addressId != null) {
                AddressClient.AddressDto address = clients.address().fetch(addressId, bearerToken);
                order.setShippingLabel(address.label());
                order.setShippingLine1(address.line1());
                order.setShippingLine2(address.line2());
                order.setShippingCity(address.city());
                order.setShippingState(address.state());
                order.setShippingPincode(address.pincode());
                order.setShippingPhone(address.phone());
            }
            order.addStatusEvent(OrderStatus.PENDING_PAYMENT, "Order placed");

            // The only DB write of order placement, in its own short transaction. If it fails
            // (e.g. the concurrent idempotency-key race the controller recovers from) the
            // catch below compensates the stock/coupon already committed remotely.
            return tx.execute(status -> new OrderCreationResult(toResponse(repo.save(order)), true));

        } catch (RuntimeException failure) {
            // Lost a race against a concurrent request carrying the SAME Idempotency-Key (a
            // double-click). Both requests derived the same reservation ids (see sagaId), so
            // inventory-svc de-duplicated our reserve against the winner's — the stock and
            // coupon we think we hold ARE the winner's. Compensating would "undo" that shared
            // reservation and hand back stock the winner's order depends on (overselling, and a
            // double restock if that order is later cancelled). The controller answers this
            // request with the winner's order, so just propagate.
            if (idempotencyKey != null && failure instanceof DataIntegrityViolationException
                    && repo.findByUserIdAndIdempotencyKey(userId, idempotencyKey).isPresent()) {
                log.info("Concurrent request with the same Idempotency-Key already created the order; "
                        + "not releasing the shared reservation");
                throw failure;
            }
            // Unwinds EVERYTHING committed above this point — reserved
            // stock AND a committed coupon redemption, whichever of them
            // actually happened before the failure. This is the fix for the
            // orphaned-reservation gap the original audit found: previously
            // only the reservation loop was covered, so a failure in the
            // order-save step itself (e.g. the concurrent idempotency-key
            // race below) left committed side effects with nothing to show
            // for them.
            String compensationCouponCode = committedCouponCode;
            String compensationCouponOperationId = couponOperationId;
            if (compensationCouponCode == null && couponOperationId != null
                    && !(failure instanceof CouponInvalidException) && couponCode != null && !couponCode.isBlank()) {
                // The commit call may have timed out after promotions-svc
                // committed the redemption. Releasing the same operation id
                // is safe whether the commit actually landed or not.
                compensationCouponCode = couponCode;
                compensationCouponOperationId = couponOperationId;
            }
            compensate(reserved, compensationCouponCode, compensationCouponOperationId,
                    "attempt-" + reservationId);
            throw failure;
        }
    }

    /**
     * Backwards-compatible overload for non-browser callers. Browser traffic should
     * use the idempotency-key overload so a payment that succeeds but times out on
     * the response can be replayed safely.
     */
    public OrderPaymentResult payOrder(Long orderId, Long userId, String role, PayOrderRequest req,
            String userEmail) {
        return payOrderInternal(orderId, userId, role, req, userEmail, null);
    }

    public OrderPaymentResult payOrder(Long orderId, Long userId, String role, PayOrderRequest req,
            String userEmail, String idempotencyKey) {
        return payOrderInternal(orderId, userId, role, req, userEmail, idempotencyKey);
    }

    /**
     * Pays for an order in three steps, so that no DB connection or row lock is held while
     * payment-svc is being called (up to ~11s):
     *
     * 1. claim   (short tx, row-locked): PENDING_PAYMENT -> PAYMENT_PROCESSING. This is the
     *            mutual exclusion that used to be the row lock — payment-svc only dedupes by
     *            Idempotency-Key, so two requests with different keys MUST NOT both reach it.
     *            The second request finds PAYMENT_PROCESSING and is rejected immediately.
     * 2. charge  (no tx): call payment-svc. If the call throws, the outcome is unknown; the
     *            claim is released back to PENDING_PAYMENT so the customer can retry, and the
     *            browser's stable Idempotency-Key makes payment-svc replay the first result
     *            instead of charging again.
     * 3. settle  (short tx, row-locked): CONFIRMED on success, CANCELLED (+ stock/coupon
     *            release) on decline.
     *
     * A pod that dies between 1 and 3 strands the order in PAYMENT_PROCESSING;
     * PendingOrderExpiryJob returns such orders to PENDING_PAYMENT.
     */
    private OrderPaymentResult payOrderInternal(Long orderId, Long userId, String role, PayOrderRequest req,
            String userEmail, String idempotencyKey) {
        BigDecimal amount = claimForPayment(orderId, userId, role);

        PaymentClient.PaymentOutcome payment;
        try {
            payment = (idempotencyKey == null || idempotencyKey.isBlank())
                    ? clients.payment().process(orderId, userId, amount, req)
                    : clients.payment().process(orderId, userId, amount, req, idempotencyKey);
        } catch (RuntimeException unknownOutcome) {
            releasePaymentClaimQuietly(orderId);
            throw unknownOutcome;
        }

        return settlePayment(orderId, amount, req, userEmail, payment);
    }

    /** Step 1: atomically PENDING_PAYMENT -> PAYMENT_PROCESSING; returns the amount to charge. */
    private BigDecimal claimForPayment(Long orderId, Long userId, String role) {
        return tx.execute(status -> {
            Order order = repo.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));

            assertCanAccess(order, userId, role);

            if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                throw new InvalidOrderStateException(
                        "Order " + orderId + " is not awaiting payment (current status: " + order.getStatus() + ")");
            }

            order.setStatus(OrderStatus.PAYMENT_PROCESSING);
            order.setPaymentStartedAt(Instant.now());
            repo.save(order);
            return order.getTotalAmount();
        });
    }

    /**
     * Result of step 3. {@code order} is null when the claim was lost.
     */
    private record Settled(OrderResponse order, OrderStatus statusFound) {
    }

    /**
     * Step 3: record the payment outcome.
     */
    private OrderPaymentResult settlePayment(
            Long orderId,
            BigDecimal amount,
            PayOrderRequest req,
            String userEmail,
            PaymentClient.PaymentOutcome payment) {

        Settled settled = tx.execute(status -> settlePaymentInTransaction(orderId, req, userEmail, payment));

        if (settled.order() == null) {
            boolean refunded = refundLatePaymentIfRequired(orderId, amount, payment, settled);

            throw buildLatePaymentException(orderId, payment, settled.statusFound(), refunded);
        }

        return new OrderPaymentResult(settled.order(), payment.succeeded());
    }

    /**
     * Persists the payment result in a short transaction.
     *
     * PENDING_PAYMENT is tolerated because the stale-claim sweep may have returned
     * the order to PENDING_PAYMENT while payment-svc was still processing.
     */
    private Settled settlePaymentInTransaction(
            Long orderId,
            PayOrderRequest req,
            String userEmail,
            PaymentClient.PaymentOutcome payment) {

        Order order = txLockedOrder(orderId);

        if (!isPaymentSettleable(order)) {
            return new Settled(null, order.getStatus());
        }

        applyPaymentOutcome(order, req, payment, userEmail);

        Order saved = repo.save(order);

        publishConfirmationEventIfNeeded(saved, userEmail, payment);

        return new Settled(toResponse(saved), saved.getStatus());
    }

    private Order txLockedOrder(Long orderId) {
        return repo.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private boolean isPaymentSettleable(Order order) {
        return order.getStatus() == OrderStatus.PAYMENT_PROCESSING
                || order.getStatus() == OrderStatus.PENDING_PAYMENT;
    }

    private void applyPaymentOutcome(
            Order order,
            PayOrderRequest req,
            PaymentClient.PaymentOutcome payment,
            String userEmail) {

        if (payment.succeeded()) {
            applySuccessfulPayment(order, req, payment, userEmail);
            return;
        }

        applyDeclinedPayment(order);
    }

    private void applySuccessfulPayment(
            Order order,
            PayOrderRequest req,
            PaymentClient.PaymentOutcome payment,
            String userEmail) {

        order.setStatus(OrderStatus.CONFIRMED);
        order.setPaymentMethod(req.getMethod());
        order.setPaymentReference(payment.reference());
        order.setCustomerEmail(userEmail);

        String note = successfulPaymentNote(payment);
        order.addStatusEvent(OrderStatus.CONFIRMED, note);
    }

    private String successfulPaymentNote(PaymentClient.PaymentOutcome payment) {
        if ("COD_PENDING".equals(payment.status())) {
            return "Order confirmed — pay on delivery";
        }

        return "Payment confirmed";
    }

    private void applyDeclinedPayment(Order order) {
        order.setStatus(OrderStatus.CANCELLED);
        order.addStatusEvent(OrderStatus.CANCELLED, "Payment declined");

        // Persist the terminal local state before touching remote inventory/coupon
        // state.
        repo.save(order);
        repo.flush();

        releaseOrderSideEffects(order, "payment-failed-order-" + order.getId());
    }

    private void publishConfirmationEventIfNeeded(
            Order saved,
            String userEmail,
            PaymentClient.PaymentOutcome payment) {

        if (payment.succeeded()) {
            eventPublisher.publishEvent(
                    new OrderConfirmedEvent(
                            saved.getId(),
                            saved.getUserId(),
                            userEmail,
                            toEventItems(saved),
                            saved.getTotalAmount()));
        }
    }

    private boolean refundLatePaymentIfRequired(
            Long orderId,
            BigDecimal amount,
            PaymentClient.PaymentOutcome payment,
            Settled settled) {

        if (!payment.succeeded() || "COD_PENDING".equals(payment.status())) {
            return false;
        }

        try {
            clients.refund().refund(orderId, amount, "late-payment-" + orderId);
            return true;
        } catch (RuntimeException e) {
            log.error(
                    "Order {} was {} when its payment completed and the automatic refund FAILED"
                            + " — refund manually: {}",
                    orderId,
                    settled.statusFound(),
                    e.getMessage());

            return false;
        }
    }

    private InvalidOrderStateException buildLatePaymentException(
            Long orderId,
            PaymentClient.PaymentOutcome payment,
            OrderStatus statusFound,
            boolean refunded) {

        String message = "Order " + orderId
                + " was " + statusFound
                + " while its payment was being processed";

        if (payment.succeeded()) {
            message += latePaymentRefundMessage(refunded);
        }

        return new InvalidOrderStateException(message);
    }

    private String latePaymentRefundMessage(boolean refunded) {
        if (refunded) {
            return "; the payment has been refunded";
        }

        return "; the payment could not be refunded automatically and will be reviewed";
    }

    /** Unknown payment outcome: give the order back so the customer (or the sweep) can retry. */
    private void releasePaymentClaimQuietly(Long orderId) {
        try {
            tx.executeWithoutResult(status -> repo.findByIdForUpdate(orderId)
                    .filter(o -> o.getStatus() == OrderStatus.PAYMENT_PROCESSING).ifPresent(o -> {
                        o.setStatus(OrderStatus.PENDING_PAYMENT);
                        repo.save(o);
                    }));
        } catch (RuntimeException e) {
            // Not fatal: releaseStalePaymentClaim (PendingOrderExpiryJob) recovers it.
            log.warn("Could not release the payment claim on order {}: {}", orderId, e.getMessage());
        }
    }

    /**
     * Returns an order stranded in PAYMENT_PROCESSING (its request died mid-payment) to
     * PENDING_PAYMENT. Safe for the customer to retry: the browser reuses its Idempotency-Key, so
     * if the first attempt did reach payment-svc the retry replays that result rather than
     * charging again. Called by PendingOrderExpiryJob.
     *
     * @return true if this call released the claim
     */
    @Transactional
    public boolean releaseStalePaymentClaim(Long orderId, Instant claimedBefore) {
        Order order = repo.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PAYMENT_PROCESSING) {
            return false;
        }
        Instant claimedAt = order.getPaymentStartedAt();
        if (claimedAt != null && !claimedAt.isBefore(claimedBefore)) {
            return false; // claimed again since the sweep listed it
        }
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        repo.save(order);
        log.warn("Order {} was stuck in PAYMENT_PROCESSING since {}; returned to PENDING_PAYMENT. If the customer"
                + " reports a charge without a confirmed order, reconcile with payment-svc.", orderId, claimedAt);
        return true;
    }

    @Transactional
    public OrderResponse updateStatus(Long orderId, OrderStatus newStatus) {
        Order order = repo.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        Set<OrderStatus> allowed = ALLOWED_STATUS_TRANSITIONS.getOrDefault(order.getStatus(), Set.of());
        if (!allowed.contains(newStatus)) {
            throw new InvalidOrderStateException(
                    "Cannot move order " + orderId + " from " + order.getStatus() + " to " + newStatus);
        }
        order.setStatus(newStatus);
        order.addStatusEvent(newStatus, switch (newStatus) {
        case SHIPPED -> "Order shipped";
        case DELIVERED -> "Order delivered";
        default -> "Status updated to " + newStatus;
        });
        return toResponse(repo.save(order));
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> findExistingByIdempotencyKey(Long userId, String idempotencyKey) {
        idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        if (idempotencyKey == null)
            return Optional.empty();
        return repo.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PagedResponse<OrderResponse> listOrders(Long userId, String role, Pageable pageable) {
        Page<Order> page = "ADMIN".equalsIgnoreCase(role) ? repo.findAll(pageable)
                : repo.findByUserId(userId, pageable);
        return PagedResponse.from(page, page.getContent().stream().map(this::toResponse).toList());
    }

    @Transactional(readOnly = true)
    public boolean isVerifiedPurchase(Long userId, Long productId) {
        return repo.existsDeliveredOrderWithProduct(userId, productId);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id, Long userId, String role) {
        Order order = repo.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        assertCanAccess(order, userId, role);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderTrackingResponse getTracking(Long id, Long userId, String role) {
        Order order = repo.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        assertCanAccess(order, userId, role);
        List<TrackingEventResponse> events = order.getStatusEvents().stream()
                .sorted(java.util.Comparator.comparing(OrderStatusEvent::getCreatedAt))
                .map(e -> new TrackingEventResponse(e.getStatus(), e.getNote(), e.getCreatedAt())).toList();
        return new OrderTrackingResponse(order.getId(), order.getStatus(), events);
    }

    // Fixed, illustrative mock values — this app doesn't have a real seller
    // registry or tax engine. taxRatePercent (18%) is a common Indian GST
    // slab, chosen because it's the most plausible default for this app's
    // apparent market, not because it's correct for every product category
    // a real GST invoice would need to distinguish.
    private static final String SELLER_NAME = "Catalogix Retail Pvt. Ltd.";
    private static final String SELLER_ADDRESS = "3rd Floor, Tech Park One, Chandigarh, Punjab 160101, India";
    private static final BigDecimal TAX_RATE_PERCENT = new BigDecimal("18.00");

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoice(Long id, Long userId, String role) {
        Order order = repo.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        assertCanAccess(order, userId, role);

        if (order.getPaymentMethod() == null) {
            throw new InvalidOrderStateException("No invoice available for order " + id + " — it was never paid for");
        }

        List<InvoiceLineResponse> lines = order.getItems().stream().map(
                i -> new InvoiceLineResponse(i.getProductName(), i.getQuantity(), i.getUnitPrice(), i.getSubtotal()))
                .toList();
        BigDecimal itemsSubtotal = lines.stream().map(InvoiceLineResponse::subtotal).reduce(BigDecimal.ZERO,
                BigDecimal::add);

        // totalAmount is what payment-svc actually captured — treated as
        // tax-INCLUSIVE and reverse-derived into taxableValue + taxAmount
        // (rather than computed independently and added on top) specifically
        // so the two always sum back to exactly totalAmount, with no
        // rounding gap between what this invoice shows and what was charged.
        BigDecimal totalAmount = order.getTotalAmount();
        BigDecimal taxDivisor = BigDecimal.ONE.add(TAX_RATE_PERCENT.divide(new BigDecimal("100")));
        BigDecimal taxableValue = totalAmount.divide(taxDivisor, 2, java.math.RoundingMode.HALF_UP);
        BigDecimal taxAmount = totalAmount.subtract(taxableValue);

        InvoiceResponse invoice = new InvoiceResponse();
        invoice.setInvoiceNumber("INV-" + String.format("%08d", order.getId()));
        invoice.setOrderId(order.getId());
        invoice.setOrderDate(order.getCreatedAt());
        invoice.setSellerName(SELLER_NAME);
        invoice.setSellerAddress(SELLER_ADDRESS);
        invoice.setCustomerEmail(order.getCustomerEmail());
        invoice.setBillingAddress(order.getShippingLine1() == null ? null
                : new ShippingAddressSummary(order.getShippingLabel(), order.getShippingLine1(),
                        order.getShippingLine2(), order.getShippingCity(), order.getShippingState(),
                        order.getShippingPincode(), order.getShippingPhone()));
        invoice.setItems(lines);
        invoice.setItemsSubtotal(itemsSubtotal);
        invoice.setDiscountAmount(order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO);
        invoice.setTaxableValue(taxableValue);
        invoice.setTaxRatePercent(TAX_RATE_PERCENT);
        invoice.setTaxAmount(taxAmount);
        invoice.setTotalAmount(totalAmount);
        invoice.setPaymentMethod(order.getPaymentMethod());
        invoice.setPaymentReference(order.getPaymentReference());
        return invoice;
    }

    @Transactional
    public OrderResponse cancelOrder(Long id, Long userId, String role, String userEmail) {
        // Row-locked read — see payOrder: a cancel racing a payment (or a second
        // cancel)
        // must wait for the other to finish rather than act on stale status.
        Order order = repo.findByIdForUpdate(id).orElseThrow(() -> new OrderNotFoundException(id));
        assertCanAccess(order, userId, role);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            return toResponse(order);
        }
        if (order.getStatus() == OrderStatus.PAYMENT_PROCESSING) {
            throw new InvalidOrderStateException(
                    "A payment for order " + id + " is being processed right now; try cancelling again in a moment");
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException(
                    "Order " + id + " can no longer be cancelled (current status: " + order.getStatus() + ")");
        }

        if (order.getStatus() == OrderStatus.CONFIRMED && order.getPaymentMethod() != null
                && order.getPaymentMethod() != PaymentMethod.COD) {
            try {
                clients.refund().refund(order.getId(), order.getTotalAmount(), "cancel-order-" + order.getId());
            } catch (RuntimeException e) {
                throw new RefundFailedException("Refund failed for cancelled order " + id);
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        boolean isOwnCancellation = order.getUserId() != null && order.getUserId().equals(userId);
        order.addStatusEvent(OrderStatus.CANCELLED, isOwnCancellation ? "Cancelled by customer" : "Cancelled by admin");
        // Flush the local transition before compensating remote inventory/coupon state.
        // The
        // outbox protects a failed remote call; this flush also protects against doing
        // the
        // remote release first and then losing the local cancellation update.
        repo.save(order);
        repo.flush();
        releaseOrderSideEffects(order, "cancel-order-" + id);

        Order saved = repo.save(order);
        eventPublisher.publishEvent(
                new OrderCancelledEvent(saved.getId(), saved.getUserId(), userEmail, toEventItems(saved)
            )
        );
        return toResponse(saved);
    }

    /**
     * Cancels an order that was placed but never paid, giving its reserved stock
     * and coupon back. Called by PendingOrderExpiryJob. Without this, every
     * abandoned checkout kept its stock reserved forever. Row-locked and
     * status-checked, so it is a no-op if the customer paid (or cancelled) in the
     * meantime.
     *
     * @return true if the order was expired by this call
     */
    @Transactional
    public boolean expireUnpaidOrder(Long orderId) {
        Order order = repo.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return false;
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.addStatusEvent(OrderStatus.CANCELLED, "Cancelled automatically: payment was not received in time");
        repo.save(order);
        repo.flush();
        releaseOrderSideEffects(order, "expire-unpaid-order-" + orderId);
        return true;
    }

    private void releaseOrderSideEffects(Order order, String outboxReason) {
        List<ReservedItem> asReserved = order.getItems().stream().map(i -> new ReservedItem(i.getProductId(),
                i.getSellerId(), i.getProductName(), i.getUnitPrice(), i.getQuantity(), null)).toList();
        compensateWithReason(asReserved, order.getAppliedCouponCode(), order.getCouponOperationId(),
                outboxReason, "order-" + order.getId(), false);
    }

    private void compensate(List<ReservedItem> reserved, String couponCode, String couponOperationId, String scope) {
        compensateWithReason(reserved, couponCode, couponOperationId, "compensate-failed-order-creation",
                scope, true);
    }

    // scope makes each release's idempotency id unique to ONE logical release
    // ("attempt-<uuid>"
    // for a failed order creation, "order-<id>" for a cancel/expiry), so retrying
    // it — live, or
    // later from the outbox after a crash — releases each line at most once.
    private void compensateWithReason(List<ReservedItem> reserved, String couponCode, String couponOperationId,
            String reason, String scope, boolean independentOutbox) {
        int lineIndex = 0;
        for (ReservedItem r : reserved) {
            String releaseOp = "release:" + scope + ":" + lineIndex++;
            try {
                clients.inventory().adjust(r.productId(), r.quantity(), releaseOp, r.reserveOperationId());
            } catch (RuntimeException compensationError) {
                log.warn("Live stock-release failed for product {} ({}), queuing to outbox: {}", r.productId(), reason,
                        compensationError.getMessage());
                CompensationOutbox entry = CompensationOutbox.releaseStock(r.productId(), r.quantity(), reason,
                        releaseOp, r.reserveOperationId());
                persistCompensation(entry, independentOutbox);
            }
        }
        if (couponCode != null) {
            try {
                if (couponOperationId == null || couponOperationId.isBlank()) {
                    clients.promotions().release(couponCode);
                } else {
                    clients.promotions().release(couponCode, couponOperationId);
                }
            } catch (RuntimeException compensationError) {
                log.warn("Live coupon-release failed for {} ({}), queuing to outbox: {}", couponCode, reason,
                        compensationError.getMessage());
                CompensationOutbox entry = CompensationOutbox.releaseCoupon(couponCode, reason, couponOperationId);
                persistCompensation(entry, independentOutbox);
            }
        }
    }

    private void persistCompensation(CompensationOutbox entry, boolean independent) {
        if (independent) {
            outboxWriter.enqueueIndependent(entry);
        } else {
            outboxWriter.enqueue(entry);
        }
    }

    /**
     * Normalizes the persisted/request-scoped idempotency key and keeps it aligned
     * with the orders.idempotency_key VARCHAR(64) contract. Blank headers are
     * treated as absent.
     */
    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return normalized;
    }

    /**
     * Makes the saga operation id stable across retries when the caller supplied an
     * Idempotency-Key. Without this, a checkout that timed out after inventory
     * reservation could retry with a brand-new reservation id and leave the first
     * reservation unreconciled. No key means the legacy/direct call remains unique.
     */
    private String sagaId(Long userId, String idempotencyKey) {
        idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        if (idempotencyKey == null) {
            return java.util.UUID.randomUUID().toString();
        }
        String input = userId + ":" + idempotencyKey;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "key-" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for checkout idempotency ids", e);
        }
    }

    private void assertCanAccess(Order order, Long userId, String role) {
        boolean isOwner = order.getUserId() != null && order.getUserId().equals(userId);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(role);
        if (!isOwner && !isAdmin) {
            throw new ForbiddenException("You may only view or manage your own orders");
        }
    }

    private List<OrderItemEventData> toEventItems(Order order) {
        return order.getItems().stream().map(i -> new OrderItemEventData(i.getProductId(), i.getSellerId(),
                i.getProductName(), i.getQuantity(), i.getUnitPrice(), i.getSubtotal())).toList();
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream().map(i -> {
            OrderItemResponse response = new OrderItemResponse(i.getProductId(), i.getProductName(), i.getQuantity(),
                    i.getUnitPrice(), i.getSubtotal());
            response.setSellerId(i.getSellerId());
            return response;
        }).toList();
        ShippingAddressSummary shippingAddress = order.getShippingLine1() == null ? null
                : new ShippingAddressSummary(order.getShippingLabel(), order.getShippingLine1(),
                        order.getShippingLine2(), order.getShippingCity(), order.getShippingState(),
                        order.getShippingPincode(), order.getShippingPhone());
        OrderResponse response = new OrderResponse(order.getId(), order.getUserId(), order.getStatus(),
                order.getTotalAmount(), order.getCreatedAt(), items);
        response.setAppliedCouponCode(order.getAppliedCouponCode());
        response.setDiscountAmount(order.getDiscountAmount());
        response.setShippingAddress(shippingAddress);
        return response;
    }
}