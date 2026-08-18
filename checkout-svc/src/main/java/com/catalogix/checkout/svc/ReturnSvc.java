package com.catalogix.checkout.svc;

import com.catalogix.checkout.client.InventoryClient;
import com.catalogix.checkout.client.RefundClient;
import com.catalogix.checkout.dto.*;
import com.catalogix.checkout.exception.ForbiddenException;
import com.catalogix.checkout.exception.InvalidReturnException;
import com.catalogix.checkout.exception.OrderNotFoundException;
import com.catalogix.checkout.exception.RefundFailedException;
import com.catalogix.checkout.exception.ReturnRequestNotFoundException;
import com.catalogix.checkout.model.*;
import com.catalogix.checkout.repository.OrderRepository;
import com.catalogix.checkout.repository.ReturnRequestRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Return/refund — the one flow in this app that reuses the checkout saga's
 * mindset (reserve-then-compensate) without reusing its actual outbox
 * machinery. See approve()'s Javadoc for exactly where that line is drawn
 * and why.
 */
@Service
public class ReturnSvc {

    private static final Logger log = LoggerFactory.getLogger(ReturnSvc.class);

    // Real storefronts vary this by category; a single flat window is the
    // right level of realism for this app's scope.
    private static final int RETURN_WINDOW_DAYS = 7;

    private final OrderRepository orderRepo;
    private final ReturnRequestRepository returnRepo;
    private final InventoryClient inventoryClient;
    private final RefundClient refundClient;

    public ReturnSvc(OrderRepository orderRepo, ReturnRequestRepository returnRepo,
                      InventoryClient inventoryClient, RefundClient refundClient) {
        this.orderRepo = orderRepo;
        this.returnRepo = returnRepo;
        this.inventoryClient = inventoryClient;
        this.refundClient = refundClient;
    }

    @Transactional
    public ReturnResponse requestReturn(Long orderId, Long userId, String role, RequestReturnRequest req) {
        Order order = orderRepo.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        assertCanAccess(order, userId, role);

        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new InvalidReturnException(
                    "Order " + orderId + " cannot be returned — it must be delivered first (current status: "
                    + order.getStatus() + ")");
        }

        Instant deliveredAt = order.getStatusEvents().stream()
                .filter(e -> e.getStatus() == OrderStatus.DELIVERED)
                .map(OrderStatusEvent::getCreatedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new InvalidReturnException(
                        "Order " + orderId + " has no recorded delivery date"));

        if (Instant.now().isAfter(deliveredAt.plus(RETURN_WINDOW_DAYS, ChronoUnit.DAYS))) {
            throw new InvalidReturnException(
                    "The " + RETURN_WINDOW_DAYS + "-day return window for order " + orderId + " has expired");
        }

        Map<Long, Integer> alreadyReturned = alreadyReturnedQuantities(orderId);

        ReturnRequest returnRequest = new ReturnRequest();
        returnRequest.setOrder(order);
        returnRequest.setUserId(userId);
        returnRequest.setReason(req.getReason());

        BigDecimal refundAmount = BigDecimal.ZERO;
        for (ReturnItemRequest itemReq : req.getItems()) {
            OrderItem original = order.getItems().stream()
                    .filter(i -> i.getProductId().equals(itemReq.getProductId()))
                    .findFirst()
                    .orElseThrow(() -> new InvalidReturnException(
                            "Product " + itemReq.getProductId() + " was not part of order " + orderId));

            int returnedSoFar = alreadyReturned.getOrDefault(itemReq.getProductId(), 0);
            int available = original.getQuantity() - returnedSoFar;
            if (itemReq.getQuantity() > available) {
                throw new InvalidReturnException(
                        "Cannot return " + itemReq.getQuantity() + " of product " + itemReq.getProductId()
                        + " — only " + available + " remaining eligible for return");
            }

            ReturnItem item = new ReturnItem(original.getProductId(), original.getProductName(),
                    itemReq.getQuantity(), original.getUnitPrice());
            returnRequest.addItem(item);
            refundAmount = refundAmount.add(item.getSubtotal());
        }
        returnRequest.setRefundAmount(refundAmount);

        return toResponse(returnRepo.save(returnRequest));
    }

    @Transactional(readOnly = true)
    public List<ReturnResponse> listMine(Long userId) {
        return returnRepo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ReturnResponse getOne(Long id, Long userId, String role) {
        ReturnRequest rr = returnRepo.findById(id).orElseThrow(() -> new ReturnRequestNotFoundException(id));
        assertCanAccess(rr.getOrder(), userId, role);
        return toResponse(rr);
    }

    // Admin-only — enforced at the controller, same convention as
    // checkout-svc's other admin endpoints (see AdminController).
    @Transactional(readOnly = true)
    public PagedResponse<ReturnResponse> listAll(ReturnStatus statusFilter, Pageable pageable) {
        Page<ReturnRequest> page = statusFilter != null
                ? returnRepo.findByStatusOrderByCreatedAtDesc(statusFilter, pageable)
                : returnRepo.findAllByOrderByCreatedAtDesc(pageable);
        return PagedResponse.from(page, page.getContent().stream().map(this::toResponse).toList());
    }

    /**
     * Refunds (unless COD — nothing was captured, so nothing to reverse)
     * then restocks. The two are NOT both wrapped in the same all-or-nothing
     * guarantee the checkout saga gives its own reserve/commit steps: a
     * refund is a real, already-executed side effect the moment
     * RefundClient returns successfully, and this local @Transactional
     * rollback can undo our own database row, but it can't call payment-svc
     * back and un-refund money that already moved. Extending the
     * checkout-svc's outbox/compensation machinery to cover this too would
     * be the fully-correct fix; it's deliberately not done here to keep
     * this feature's scope contained, and is called out plainly rather than
     * silently assumed away. The ordering below reflects that: refund
     * happens first (the customer-facing promise that matters most), and a
     * restock failure afterward is logged and swallowed rather than allowed
     * to make the return look like it failed when the money already moved.
     */
    @Transactional
    public ReturnResponse approve(Long id) {
        ReturnRequest rr = returnRepo.findById(id).orElseThrow(() -> new ReturnRequestNotFoundException(id));
        if (rr.getStatus() != ReturnStatus.REQUESTED) {
            throw new InvalidReturnException("Return " + id + " has already been decided (" + rr.getStatus() + ")");
        }

        Order order = rr.getOrder();
        String decisionNote;

        if (order.getPaymentMethod() == PaymentMethod.COD) {
            decisionNote = "Refunded (COD order — no payment was captured to reverse)";
        } else {
            try {
                RefundClient.RefundOutcome outcome = refundClient.refund(order.getId(), rr.getRefundAmount());
                decisionNote = "Refunded: " + outcome.reference();
            } catch (RuntimeException e) {
                throw new RefundFailedException("Refund failed for return " + id + ": " + e.getMessage());
            }
        }

        for (ReturnItem item : rr.getItems()) {
            try {
                inventoryClient.adjust(item.getProductId(), item.getQuantity());
            } catch (RuntimeException e) {
                // See this method's Javadoc — the refund above already
                // happened and won't be undone by this failing. Logged for
                // manual reconciliation rather than blocking the refund.
                log.error("Restock failed for return {} product {} — refund already processed, "
                        + "manual inventory reconciliation needed", id, item.getProductId(), e);
            }
        }

        rr.setStatus(ReturnStatus.REFUNDED);
        rr.setDecisionNote(decisionNote);
        rr.setDecidedAt(Instant.now());
        return toResponse(returnRepo.save(rr));
    }

    @Transactional
    public ReturnResponse reject(Long id, RejectReturnRequest req) {
        ReturnRequest rr = returnRepo.findById(id).orElseThrow(() -> new ReturnRequestNotFoundException(id));
        if (rr.getStatus() != ReturnStatus.REQUESTED) {
            throw new InvalidReturnException("Return " + id + " has already been decided (" + rr.getStatus() + ")");
        }
        rr.setStatus(ReturnStatus.REJECTED);
        rr.setDecisionNote(req.getReason());
        rr.setDecidedAt(Instant.now());
        return toResponse(returnRepo.save(rr));
    }

    private Map<Long, Integer> alreadyReturnedQuantities(Long orderId) {
        Map<Long, Integer> totals = new HashMap<>();
        for (ReturnRequest rr : returnRepo.findByOrderIdAndStatusIn(orderId,
                List.of(ReturnStatus.REQUESTED, ReturnStatus.REFUNDED))) {
            for (ReturnItem item : rr.getItems()) {
                totals.merge(item.getProductId(), item.getQuantity(), Integer::sum);
            }
        }
        return totals;
    }

    private void assertCanAccess(Order order, Long userId, String role) {
        boolean isOwner = order.getUserId() != null && order.getUserId().equals(userId);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(role);
        if (!isOwner && !isAdmin) {
            throw new ForbiddenException("You may only view or manage your own returns");
        }
    }

    private ReturnResponse toResponse(ReturnRequest rr) {
        List<ReturnItemResponse> items = rr.getItems().stream()
                .map(item -> new ReturnItemResponse(
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()
                ))
                .toList();

        ReturnResponse response = new ReturnResponse();
        response.setId(rr.getId());
        response.setOrderId(rr.getOrder().getId());
        response.setUserId(rr.getUserId());
        response.setReason(rr.getReason());
        response.setStatus(rr.getStatus());
        response.setRefundAmount(rr.getRefundAmount());
        response.setDecisionNote(rr.getDecisionNote());
        response.setCreatedAt(rr.getCreatedAt());
        response.setDecidedAt(rr.getDecidedAt());
        response.setItems(items);

        return response;
    }
}
