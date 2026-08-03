package com.catalogix.order.svc;

import com.catalogix.order.client.ProductSvcClient;
import com.catalogix.order.dto.*;
import com.catalogix.order.exception.ForbiddenException;
import com.catalogix.order.exception.InvalidOrderStateException;
import com.catalogix.order.exception.OrderNotFoundException;
import com.catalogix.order.model.*;
import com.catalogix.order.repository.OrderRepository;
import com.catalogix.order.repository.StockAdjustmentOutboxRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Places, pays for, ships, delivers, and cancels orders. Talks to product-svc
 * (via ProductSvcClient, circuit-breaker-guarded) to price items and
 * reserve/restore stock, forwarding the placing user's own bearer token so
 * product-svc authorizes the call as that same user.
 *
 * Lifecycle: PENDING_PAYMENT -[pay: success]-> CONFIRMED -> SHIPPED -> DELIVERED
 *                            -[pay: failure]-> CANCELLED (stock released)
 *            PENDING_PAYMENT/CONFIRMED -[cancel]-> CANCELLED (stock released)
 * Stock is reserved at order *creation* (PENDING_PAYMENT), not at payment —
 * otherwise nothing would stop two people "buying" the last unit while both
 * are mid-checkout.
 *
 * Reliability notes:
 * - Idempotency: passing the same Idempotency-Key for a retried "place order"
 *   request returns the original order rather than creating a duplicate.
 * - Compensation: if reserving item N of an order fails, items 1..N-1 are
 *   rolled back. If that rollback call itself fails live, it's queued to the
 *   stock_adjustment_outbox table instead of just being logged and dropped —
 *   see StockAdjustmentOutboxProcessor for the retry loop. The same applies
 *   to releasing stock on cancellation or a failed payment.
 * - Circuit breaker: ProductSvcClient fails fast instead of hammering a down
 *   product-svc; see application.properties for the "productSvc" instance config.
 *
 * This still isn't a full distributed saga (no cross-service 2PC), but between
 * idempotency, the outbox, and the circuit breaker, the specific failure modes
 * that would otherwise silently corrupt stock or double-charge a customer are
 * now covered.
 */
@Service
public class OrderSvc {

    private static final Logger log = LoggerFactory.getLogger(OrderSvc.class);

    // Only these forward transitions are allowed via updateStatus(); payment
    // (PENDING_PAYMENT -> CONFIRMED) and cancellation have their own dedicated
    // methods with extra side effects (stock release, coupon release, etc.).
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_STATUS_TRANSITIONS = Map.of(
            OrderStatus.CONFIRMED, Set.of(OrderStatus.SHIPPED),
            OrderStatus.SHIPPED, Set.of(OrderStatus.DELIVERED)
    );

    private final OrderRepository repo;
    private final StockAdjustmentOutboxRepository outboxRepo;
    private final ProductSvcClient productSvcClient;
    private final OrderNotifier orderNotifier;
    private final CouponSvc couponSvc;
    private final PaymentSvc paymentSvc;

    public OrderSvc(
            OrderRepository repo,
            StockAdjustmentOutboxRepository outboxRepo,
            ProductSvcClient productSvcClient,
            OrderNotifier orderNotifier,
            CouponSvc couponSvc,
            PaymentSvc paymentSvc
    ) {
        this.repo = repo;
        this.outboxRepo = outboxRepo;
        this.productSvcClient = productSvcClient;
        this.orderNotifier = orderNotifier;
        this.couponSvc = couponSvc;
        this.paymentSvc = paymentSvc;
    }

    // Holds the priced product alongside the requested quantity while we
    // build up the order, so we know exactly what to compensate if a later
    // item in the same order fails to reserve.
    private record ReservedItem(ProductLookupResponse product, int quantity) {
    }

    public record OrderCreationResult(OrderResponse order, boolean wasNew) {
    }

    public record OrderPaymentResult(OrderResponse order, PaymentResponse payment) {
    }

    @Transactional
    public OrderCreationResult createOrder(
            Long userId, CreateOrderRequest req, String bearerToken, String idempotencyKey, String userEmail
    ) {
        if (idempotencyKey != null) {
            Optional<Order> existing = repo.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return new OrderCreationResult(toResponse(existing.get()), false);
            }
        }

        List<ReservedItem> reserved = new ArrayList<>();
        Coupon appliedCoupon = null;
        try {
            for (OrderItemRequest itemReq : req.getItems()) {
                ProductLookupResponse product = productSvcClient.fetchProduct(itemReq.getProductId(), bearerToken);
                productSvcClient.adjustStock(itemReq.getProductId(), -itemReq.getQuantity(), bearerToken);
                reserved.add(new ReservedItem(product, itemReq.getQuantity()));
            }
            // Validated INSIDE this try block, after stock is reserved: an invalid coupon
            // must trigger the same compensation as a failed reservation would, or the
            // already-reserved stock for this order would be left stranded with nothing to
            // show for it.
            if (req.getCouponCode() != null && !req.getCouponCode().isBlank()) {
                appliedCoupon = couponSvc.validate(req.getCouponCode());
            }
        } catch (RuntimeException failure) {
            compensate(reserved, bearerToken);
            throw failure;
        }

        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(idempotencyKey);

        BigDecimal subtotal = BigDecimal.ZERO;
        for (ReservedItem r : reserved) {
            OrderItem item = new OrderItem(
                    r.product().getId(), r.product().getName(), r.quantity(), r.product().getPrice());
            order.addItem(item);
            subtotal = subtotal.add(item.getSubtotal());
        }

        BigDecimal discount = BigDecimal.ZERO;
        if (appliedCoupon != null) {
            discount = couponSvc.calculateDiscount(appliedCoupon, subtotal);
            order.setAppliedCouponCode(appliedCoupon.getCode());
            order.setDiscountAmount(discount);
        }
        order.setTotalAmount(subtotal.subtract(discount));

        Order saved = repo.save(order);
        if (appliedCoupon != null) {
            couponSvc.recordUsage(appliedCoupon);
        }
        return new OrderCreationResult(toResponse(saved), true);
    }

    // Processes a mock payment for an order still awaiting one. Always
    // returns 200-shaped data — a declined payment isn't a server error, it's
    // a legitimate outcome the frontend needs to show (and it cancels the
    // order + releases stock/coupon usage, same as an explicit cancellation).
    @Transactional
    public OrderPaymentResult payOrder(
            Long orderId, Long userId, String role, PayOrderRequest req, String bearerToken, String userEmail
    ) {
        Order order = repo.findById(Objects.requireNonNull(orderId)).orElseThrow(() -> new OrderNotFoundException(orderId));
        assertCanAccess(order, userId, role);

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new InvalidOrderStateException(
                    "Order " + orderId + " is not awaiting payment (current status: " + order.getStatus() + ")");
        }

        PaymentResponse payment = paymentSvc.process(orderId, order.getTotalAmount(), req);

        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            order.setStatus(OrderStatus.CONFIRMED);
        } else {
            releaseOrderSideEffects(order, bearerToken, "payment-failed-order-" + orderId);
            order.setStatus(OrderStatus.CANCELLED);
        }

        Order saved = repo.save(order);
        OrderResponse response = toResponse(saved);
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            orderNotifier.notifyOrderConfirmed(userEmail, response);
        }
        return new OrderPaymentResult(response, payment);
    }

    // Admin-only forward progression: CONFIRMED -> SHIPPED -> DELIVERED.
    @Transactional
    public OrderResponse updateStatus(Long orderId, OrderStatus newStatus) {
        Order order = repo.findById(Objects.requireNonNull(orderId)).orElseThrow(() -> new OrderNotFoundException(orderId));
        Set<OrderStatus> allowed = ALLOWED_STATUS_TRANSITIONS.getOrDefault(order.getStatus(), Set.of());
        if (!allowed.contains(newStatus)) {
            throw new InvalidOrderStateException(
                    "Cannot move order " + orderId + " from " + order.getStatus() + " to " + newStatus);
        }
        order.setStatus(newStatus);
        return toResponse(repo.save(order));
    }

    // Used by the controller to recover from the rare race where two concurrent
    // requests with the same Idempotency-Key both pass the pre-check above and
    // the DB's unique constraint rejects the second insert.
    @Transactional(readOnly = true)
    public Optional<OrderResponse> findExistingByIdempotencyKey(Long userId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return repo.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PagedResponse<OrderResponse> listOrders(Long userId, String role, Pageable pageable) {
        Page<Order> page = "ADMIN".equalsIgnoreCase(role)
                ? repo.findAll(Objects.requireNonNull(pageable))
                : repo.findByUserId(userId, Objects.requireNonNull(pageable));
        return PagedResponse.from(page, page.getContent().stream().map(this::toResponse).toList());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id, Long userId, String role) {
        Order order = repo.findById(Objects.requireNonNull(id)).orElseThrow(() -> new OrderNotFoundException(id));
        assertCanAccess(order, userId, role);
        return toResponse(order);
    }

    // Cancelling restores stock (and any consumed coupon use) for every item back to product-svc.
    // Only PENDING_PAYMENT or CONFIRMED orders can be cancelled — once shipped, that's a
    // returns/refunds problem, out of scope here.
    @Transactional
    public OrderResponse cancelOrder(Long id, Long userId, String role, String bearerToken, String userEmail) {
        Order order = repo.findById(Objects.requireNonNull(id)).orElseThrow(() -> new OrderNotFoundException(id));
        assertCanAccess(order, userId, role);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            return toResponse(order);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException(
                    "Order " + id + " can no longer be cancelled (current status: " + order.getStatus() + ")");
        }

        releaseOrderSideEffects(order, bearerToken, "cancel-order-" + id);
        order.setStatus(OrderStatus.CANCELLED);

        OrderResponse response = toResponse(repo.save(order));
        orderNotifier.notifyOrderCancelled(userEmail, response);
        return response;
    }

    // Shared by cancelOrder and payOrder's decline branch: restock every item
    // (outbox-backed if the live call fails) and release any coupon use.
    private void releaseOrderSideEffects(Order order, String bearerToken, String outboxReason) {
        for (OrderItem item : order.getItems()) {
            try {
                productSvcClient.adjustStock(item.getProductId(), item.getQuantity(), bearerToken);
            } catch (RuntimeException e) {
                log.warn("Live restock failed for product {} ({}), queuing to outbox: {}",
                        item.getProductId(), outboxReason, e.getMessage());
                outboxRepo.save(new StockAdjustmentOutbox(item.getProductId(), item.getQuantity(), outboxReason));
            }
        }
        if (order.getAppliedCouponCode() != null) {
            couponSvc.releaseUsage(order.getAppliedCouponCode());
        }
    }

    private void assertCanAccess(Order order, Long userId, String role) {
        boolean isOwner = order.getUserId() != null && order.getUserId().equals(userId);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(role);
        if (!isOwner && !isAdmin) {
            throw new ForbiddenException("You may only view or manage your own orders");
        }
    }

    private void compensate(List<ReservedItem> reserved, String bearerToken) {
        for (ReservedItem r : reserved) {
            try {
                productSvcClient.adjustStock(r.product().getId(), r.quantity(), bearerToken);
            } catch (RuntimeException compensationError) {
                log.warn("Live compensation failed for product {}, queuing to outbox: {}",
                        r.product().getId(), compensationError.getMessage());
                outboxRepo.save(new StockAdjustmentOutbox(
                        r.product().getId(), r.quantity(), "compensate-failed-reservation"));
            }
        }
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> new OrderItemResponse(
                        i.getProductId(), i.getProductName(), i.getQuantity(), i.getUnitPrice(), i.getSubtotal()))
                .toList();
        return new OrderResponse(
                order.getId(), order.getUserId(), order.getStatus(), order.getTotalAmount(),
                order.getCreatedAt(), items, order.getAppliedCouponCode(), order.getDiscountAmount());
    }
}
