package com.catalogix.checkout.svc;

import com.catalogix.checkout.client.InventoryClient;
import com.catalogix.checkout.client.RefundClient;
import com.catalogix.checkout.dto.PagedResponse;
import com.catalogix.checkout.dto.RejectReturnRequest;
import com.catalogix.checkout.dto.RequestReturnRequest;
import com.catalogix.checkout.dto.ReturnItemRequest;
import com.catalogix.checkout.dto.ReturnItemResponse;
import com.catalogix.checkout.dto.ReturnResponse;
import com.catalogix.checkout.event.OrderItemEventData;
import com.catalogix.checkout.event.ReturnRefundedEvent;
import com.catalogix.checkout.exception.ForbiddenException;
import com.catalogix.checkout.exception.InvalidReturnException;
import com.catalogix.checkout.exception.OrderNotFoundException;
import com.catalogix.checkout.exception.RefundFailedException;
import com.catalogix.checkout.exception.ReturnRequestNotFoundException;
import com.catalogix.checkout.model.CompensationOutbox;
import com.catalogix.checkout.model.Order;
import com.catalogix.checkout.model.OrderItem;
import com.catalogix.checkout.model.OrderStatus;
import com.catalogix.checkout.model.OrderStatusEvent;
import com.catalogix.checkout.model.PaymentMethod;
import com.catalogix.checkout.model.ReturnItem;
import com.catalogix.checkout.model.ReturnRequest;
import com.catalogix.checkout.model.ReturnStatus;
import com.catalogix.checkout.repository.OrderRepository;
import com.catalogix.checkout.repository.ReturnRequestRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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

@Service
public class ReturnSvc {

    private static final Logger log = LoggerFactory.getLogger(ReturnSvc.class);

    /**
     * Number of days after delivery during which a return may be requested.
     */
    private static final int RETURN_WINDOW_DAYS = 7;

    private final OrderRepository orderRepo;
    private final ReturnRequestRepository returnRepo;
    private final InventoryClient inventoryClient;
    private final RefundClient refundClient;
    private final CompensationOutboxWriter outboxWriter;
    private final ApplicationEventPublisher eventPublisher;

    public ReturnSvc(
            OrderRepository orderRepo,
            ReturnRequestRepository returnRepo,
            InventoryClient inventoryClient,
            RefundClient refundClient,
            CompensationOutboxWriter outboxWriter,
            ApplicationEventPublisher eventPublisher) {

        this.orderRepo = orderRepo;
        this.returnRepo = returnRepo;
        this.inventoryClient = inventoryClient;
        this.refundClient = refundClient;
        this.outboxWriter = outboxWriter;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Creates a return request for part or all of a delivered order.
     *
     * The order row is pessimistically locked so two concurrent return
     * requests for the same order cannot both calculate the available
     * return quantity from the same stale state.
     */
    @Transactional
    public ReturnResponse requestReturn(
            Long orderId,
            Long userId,
            String role,
            RequestReturnRequest req) {

        Order order = orderRepo.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        assertCanAccess(order, userId, role);

        validateRequest(req);

        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new InvalidReturnException(
                    "Order " + orderId
                            + " cannot be returned — it must be delivered first (current status: "
                            + order.getStatus() + ")");
        }

        Instant deliveredAt = order.getStatusEvents().stream()
                .filter(event -> event.getStatus() == OrderStatus.DELIVERED)
                .map(OrderStatusEvent::getCreatedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new InvalidReturnException(
                        "Order " + orderId + " has no recorded delivery date"));

        if (Instant.now().isAfter(
                deliveredAt.plus(RETURN_WINDOW_DAYS, ChronoUnit.DAYS))) {

            throw new InvalidReturnException(
                    "The " + RETURN_WINDOW_DAYS
                            + "-day return window for order " + orderId
                            + " has expired");
        }

        Map<Long, Integer> alreadyReturned = alreadyReturnedQuantities(orderId);

        ReturnRequest returnRequest = new ReturnRequest();
        returnRequest.setOrder(order);
        returnRequest.setUserId(userId);
        returnRequest.setReason(req.getReason().trim());

        BigDecimal refundAmount = BigDecimal.ZERO;

        for (ReturnItemRequest itemReq : req.getItems()) {

            if (itemReq == null
                    || itemReq.getProductId() == null
                    || itemReq.getQuantity() == null
                    || itemReq.getQuantity() <= 0) {

                throw new InvalidReturnException(
                        "Every return item must contain a valid productId and positive quantity");
            }

            OrderItem original = order.getItems().stream()
                    .filter(item -> item.getProductId().equals(itemReq.getProductId()))
                    .findFirst()
                    .orElseThrow(() -> new InvalidReturnException(
                            "Product " + itemReq.getProductId()
                                    + " was not part of order " + orderId));

            int returnedSoFar = alreadyReturned.getOrDefault(itemReq.getProductId(), 0);

            int available = original.getQuantity() - returnedSoFar;

            if (itemReq.getQuantity() > available) {
                throw new InvalidReturnException(
                        "Cannot return " + itemReq.getQuantity()
                                + " of product " + itemReq.getProductId()
                                + " — only " + available
                                + " remaining eligible for return");
            }

            ReturnItem item = new ReturnItem(
                    original.getProductId(),
                    original.getProductName(),
                    itemReq.getQuantity(),
                    original.getUnitPrice());

            returnRequest.addItem(item);

            refundAmount = refundAmount.add(item.getSubtotal());
        }

        if (returnRequest.getItems().isEmpty()) {
            throw new InvalidReturnException(
                    "At least one item must be selected for return");
        }

        returnRequest.setRefundAmount(refundAmount);

        return toResponse(returnRepo.save(returnRequest));
    }

    @Transactional(readOnly = true)
    public List<ReturnResponse> listMine(Long userId) {
        return returnRepo
                .findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReturnResponse getOne(Long id, Long userId, String role) {
        ReturnRequest returnRequest = returnRepo.findById(id)
                .orElseThrow(() -> new ReturnRequestNotFoundException(id));

        assertCanAccess(returnRequest.getOrder(), userId, role);

        return toResponse(returnRequest);
    }

    /**
     * Admin-only operation; authorization is enforced by the controller.
     */
    @Transactional(readOnly = true)
    public PagedResponse<ReturnResponse> listAll(
            ReturnStatus statusFilter,
            Pageable pageable) {

        Page<ReturnRequest> page = statusFilter != null
                ? returnRepo.findByStatusOrderByCreatedAtDesc(
                        statusFilter, pageable)
                : returnRepo.findAllByOrderByCreatedAtDesc(pageable);

        return PagedResponse.from(
                page,
                page.getContent()
                        .stream()
                        .map(this::toResponse)
                        .toList());
    }

    /**
     * Approves a return.
     *
     * For paid orders:
     * 1. issue an idempotent refund
     * 2. restock every returned item
     * 3. queue failed restocks in the durable compensation outbox
     * 4. mark the return as REFUNDED
     *
     * For COD orders, no monetary refund is performed because no payment
     * was captured at checkout.
     *
     * The return row is pessimistically locked so two administrators cannot
     * approve the same return concurrently.
     */
    @Transactional
    public ReturnResponse approve(Long id) {

        ReturnRequest returnRequest = returnRepo.findByIdForUpdate(id)
                .orElseThrow(() -> new ReturnRequestNotFoundException(id));

        if (returnRequest.getStatus() != ReturnStatus.REQUESTED) {
            throw new InvalidReturnException(
                    "Return " + id
                            + " has already been decided ("
                            + returnRequest.getStatus() + ")");
        }

        Order order = returnRequest.getOrder();

        String decisionNote;

        /*
         * Refund idempotency is based on the return ID.
         *
         * Even if the same approval request is retried after a network
         * timeout, payment-svc receives the same idempotency key:
         *
         * return-refund-{returnId}
         *
         * so the same refund operation is not charged twice.
         */
        if (order.getPaymentMethod() == PaymentMethod.COD) {

            decisionNote = "Refunded (COD order — no payment was captured to reverse)";

        } else {

            try {
                RefundClient.RefundOutcome outcome = refundClient.refund(
                        order.getId(),
                        returnRequest.getRefundAmount(),
                        "return-refund-" + id);

                decisionNote = "Refunded: " + outcome.reference();

            } catch (RuntimeException e) {
                throw new RefundFailedException(
                        "Refund failed for return "
                                + id + ": " + e.getMessage());
            }
        }

        /*
         * Restock each item independently.
         *
         * IMPORTANT:
         * The operation ID is declared outside the try block so the exact
         * same ID can be written into the compensation outbox if the live
         * inventory call fails.
         */
        int restockIndex = 0;

        for (ReturnItem item : returnRequest.getItems()) {

            String restockOperationId = "return-restock:" + id + ":" + restockIndex++;

            try {

                inventoryClient.adjust(
                        item.getProductId(),
                        item.getQuantity(),
                        restockOperationId,
                        null);

            } catch (RuntimeException e) {

                String reason = "return-restock-failed:" + id;

                /*
                 * This MUST use REQUIRES_NEW.
                 *
                 * A successful refund followed by a failed inventory call
                 * must leave a durable retry record even if the surrounding
                 * return transaction later rolls back.
                 */
                CompensationOutbox outbox = CompensationOutbox.releaseStock(
                        item.getProductId(),
                        item.getQuantity(),
                        reason,
                        restockOperationId,
                        null);

                outboxWriter.enqueueIndependent(outbox);

                log.warn(
                        "Restock deferred for return {} product {} — "
                                + "queued compensation retry using operation {}",
                        id,
                        item.getProductId(),
                        restockOperationId,
                        e);
            }
        }

        returnRequest.setStatus(ReturnStatus.REFUNDED);
        returnRequest.setDecisionNote(decisionNote);
        returnRequest.setDecidedAt(Instant.now());

        ReturnRequest saved = returnRepo.save(returnRequest);

        ReturnResponse response = toResponse(saved);

        List<OrderItemEventData> refundedItems = saved.getItems()
                .stream()
                .map(item -> toEventItem(item, saved.getOrder()))
                .toList();

        eventPublisher.publishEvent(
                new ReturnRefundedEvent(
                        saved.getId(),
                        saved.getOrder().getId(),
                        saved.getUserId(),
                        refundedItems));

        return response;
    }

    @Transactional
    public ReturnResponse reject(
            Long id,
            RejectReturnRequest req) {

        ReturnRequest returnRequest = returnRepo.findByIdForUpdate(id)
                .orElseThrow(() -> new ReturnRequestNotFoundException(id));

        if (returnRequest.getStatus() != ReturnStatus.REQUESTED) {
            throw new InvalidReturnException(
                    "Return " + id
                            + " has already been decided ("
                            + returnRequest.getStatus() + ")");
        }

        if (req == null
                || req.getReason() == null
                || req.getReason().isBlank()) {

            throw new InvalidReturnException(
                    "A rejection reason is required");
        }

        returnRequest.setStatus(ReturnStatus.REJECTED);
        returnRequest.setDecisionNote(req.getReason().trim());
        returnRequest.setDecidedAt(Instant.now());

        return toResponse(returnRepo.save(returnRequest));
    }

    /**
     * Calculates how much quantity has already been consumed by return
     * requests for the order.
     *
     * REQUESTED counts because the original units are already reserved by
     * that pending return request.
     *
     * REFUNDED counts because those units have already been returned.
     *
     * REJECTED does not count because the units remain eligible.
     */
    private Map<Long, Integer> alreadyReturnedQuantities(Long orderId) {

        Map<Long, Integer> totals = new HashMap<>();

        List<ReturnRequest> existingReturns = returnRepo.findByOrderIdAndStatusIn(
                orderId,
                List.of(
                        ReturnStatus.REQUESTED,
                        ReturnStatus.REFUNDED));

        for (ReturnRequest existingReturn : existingReturns) {

            for (ReturnItem item : existingReturn.getItems()) {

                if (item.getProductId() != null
                        && item.getQuantity() != null) {

                    totals.merge(
                            item.getProductId(),
                            item.getQuantity(),
                            Integer::sum);
                }
            }
        }

        return totals;
    }

    private void assertCanAccess(
            Order order,
            Long userId,
            String role) {

        boolean isOwner = order.getUserId() != null
                && order.getUserId().equals(userId);

        boolean isAdmin = "ADMIN".equalsIgnoreCase(role);

        if (!isOwner && !isAdmin) {
            throw new ForbiddenException(
                    "You may only view or manage your own returns");
        }
    }

    private void validateRequest(RequestReturnRequest req) {

        if (req == null) {
            throw new InvalidReturnException(
                    "Return request cannot be null");
        }

        if (req.getReason() == null
                || req.getReason().isBlank()) {

            throw new InvalidReturnException(
                    "Return reason is required");
        }

        if (req.getItems() == null
                || req.getItems().isEmpty()) {

            throw new InvalidReturnException(
                    "At least one item must be selected for return");
        }
    }

    private OrderItemEventData toEventItem(
            ReturnItem returnItem,
            Order order) {

        Long sellerId = order.getItems()
                .stream()
                .filter(orderItem -> orderItem.getProductId() != null
                        && orderItem.getProductId()
                                .equals(returnItem.getProductId()))
                .map(OrderItem::getSellerId)
                .findFirst()
                .orElse(null);

        return new OrderItemEventData(
                returnItem.getProductId(),
                sellerId,
                returnItem.getProductName(),
                returnItem.getQuantity(),
                returnItem.getUnitPrice(),
                returnItem.getSubtotal());
    }

    private ReturnResponse toResponse(
            ReturnRequest returnRequest) {

        List<ReturnItemResponse> items = returnRequest.getItems()
                .stream()
                .map(item -> new ReturnItemResponse(
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()))
                .toList();

        ReturnResponse response = new ReturnResponse();

        response.setId(returnRequest.getId());
        response.setOrderId(returnRequest.getOrder().getId());
        response.setUserId(returnRequest.getUserId());
        response.setReason(returnRequest.getReason());
        response.setStatus(returnRequest.getStatus());
        response.setRefundAmount(returnRequest.getRefundAmount());
        response.setDecisionNote(returnRequest.getDecisionNote());
        response.setCreatedAt(returnRequest.getCreatedAt());
        response.setDecidedAt(returnRequest.getDecidedAt());
        response.setItems(items);

        return response;
    }
}