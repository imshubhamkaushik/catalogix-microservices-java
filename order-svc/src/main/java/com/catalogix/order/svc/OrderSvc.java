package com.catalogix.order.svc;

import com.catalogix.order.client.ProductSvcClient;
import com.catalogix.order.dto.*;
import com.catalogix.order.exception.ForbiddenException;
import com.catalogix.order.exception.OrderNotFoundException;
import com.catalogix.order.model.Order;
import com.catalogix.order.model.OrderItem;
import com.catalogix.order.model.OrderStatus;
import com.catalogix.order.model.StockAdjustmentOutbox;
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
import java.util.Objects;
import java.util.Optional;

/**
 * Places, lists and cancels orders. Talks to product-svc (via ProductSvcClient,
 * circuit-breaker-guarded) to price items and reserve/restore stock, forwarding
 * the placing user's own bearer token so product-svc authorizes the call as
 * that same user.
 *
 * Reliability notes:
 * - Idempotency: passing the same Idempotency-Key for a retried "place order"
 *   request returns the original order rather than creating a duplicate.
 * - Compensation: if reserving item N of an order fails, items 1..N-1 are
 *   rolled back. If that rollback call itself fails live, it's queued to the
 *   stock_adjustment_outbox table instead of just being logged and dropped —
 *   see StockAdjustmentOutboxProcessor for the retry loop.
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

    private final OrderRepository repo;
    private final StockAdjustmentOutboxRepository outboxRepo;
    private final ProductSvcClient productSvcClient;
    private final OrderNotifier orderNotifier;

    public OrderSvc(
            OrderRepository repo,
            StockAdjustmentOutboxRepository outboxRepo,
            ProductSvcClient productSvcClient,
            OrderNotifier orderNotifier
    ) {
        this.repo = repo;
        this.outboxRepo = outboxRepo;
        this.productSvcClient = productSvcClient;
        this.orderNotifier = orderNotifier;
    }

    // Holds the priced product alongside the requested quantity while we
    // build up the order, so we know exactly what to compensate if a later
    // item in the same order fails to reserve.
    private record ReservedItem(ProductLookupResponse product, int quantity) {
    }

    public record OrderCreationResult(OrderResponse order, boolean wasNew) {
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
        try {
            for (OrderItemRequest itemReq : req.getItems()) {
                ProductLookupResponse product = productSvcClient.fetchProduct(itemReq.getProductId(), bearerToken);
                productSvcClient.adjustStock(itemReq.getProductId(), -itemReq.getQuantity(), bearerToken);
                reserved.add(new ReservedItem(product, itemReq.getQuantity()));
            }
        } catch (RuntimeException failure) {
            compensate(reserved, bearerToken);
            throw failure;
        }

        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setIdempotencyKey(idempotencyKey);

        BigDecimal total = BigDecimal.ZERO;
        for (ReservedItem r : reserved) {
            OrderItem item = new OrderItem(
                    r.product().getId(), r.product().getName(), r.quantity(), r.product().getPrice());
            order.addItem(item);
            total = total.add(item.getSubtotal());
        }
        order.setTotalAmount(total);

        Order saved = repo.save(order);
        OrderResponse response = toResponse(saved);
        orderNotifier.notifyOrderConfirmed(userEmail, response);
        return new OrderCreationResult(response, true);
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

    // Cancelling restores stock for every item back to product-svc.
    @Transactional
    public OrderResponse cancelOrder(Long id, Long userId, String role, String bearerToken, String userEmail) {
        Order order = repo.findById(Objects.requireNonNull(id)).orElseThrow(() -> new OrderNotFoundException(id));
        assertCanAccess(order, userId, role);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            return toResponse(order);
        }

        for (OrderItem item : order.getItems()) {
            try {
                productSvcClient.adjustStock(item.getProductId(), item.getQuantity(), bearerToken);
            } catch (RuntimeException e) {
                log.warn("Live restock failed for product {} while cancelling order {}, queuing to outbox: {}",
                        item.getProductId(), id, e.getMessage());
                outboxRepo.save(new StockAdjustmentOutbox(
                        item.getProductId(), item.getQuantity(), "cancel-order-" + id));
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        OrderResponse response = toResponse(repo.save(order));
        orderNotifier.notifyOrderCancelled(userEmail, response);
        return response;
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
                order.getCreatedAt(), items);
    }
}
