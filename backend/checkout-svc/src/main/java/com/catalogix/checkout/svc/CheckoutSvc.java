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
import com.catalogix.checkout.repository.CompensationOutboxRepository;
import com.catalogix.checkout.repository.OrderRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
 * reached over HTTP, none of which shares a database transaction with this
 * one anymore. Orchestration (this class), not choreography, on purpose:
 * the compensation logic below is complex enough that it's worth having it
 * all in one readable place rather than scattered across five services'
 * event handlers.
 *
 * Saga shape for creating an order:
 *   1. reserve stock for each item (inventory-svc)          -- compensable
 *   2. commit coupon redemption, if any (promotions-svc)     -- compensable
 *   3. persist the order (local DB write, this service's own table)
 * If ANY step fails — including step 3 itself, e.g. the concurrent
 * idempotency-key race the controller recovers from — every side effect
 * already committed in steps 1-2 is compensated. This closes a real gap the
 * original single-service version had: its try/catch only wrapped steps 1-2,
 * so a failure in step 3 (the DB save) left reserved stock and a redeemed
 * coupon permanently orphaned with nothing to show for them. See compensate().
 *
 * Compensation itself is attempted live first; if the live call also fails,
 * it's queued to compensation_outbox instead of just being logged and
 * dropped — see CompensationOutboxProcessor for the retry loop.
 */
@Service
public class CheckoutSvc {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSvc.class);

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_STATUS_TRANSITIONS = Map.of(
            OrderStatus.CONFIRMED, Set.of(OrderStatus.SHIPPED),
            OrderStatus.SHIPPED, Set.of(OrderStatus.DELIVERED)
    );

    private final OrderRepository repo;
    private final CompensationOutboxRepository outboxRepo;
    private final CheckoutClients clients;
    private final ApplicationEventPublisher eventPublisher;

    public CheckoutSvc(
            OrderRepository repo,
            CompensationOutboxRepository outboxRepo,
            CheckoutClients clients,
            ApplicationEventPublisher eventPublisher) {
        this.repo = repo;
        this.outboxRepo = outboxRepo;
        this.clients = clients;
        this.eventPublisher = eventPublisher;
    }

    private record ReservedItem(Long productId, String productName, BigDecimal price, int quantity) {}

    public record OrderCreationResult(OrderResponse order, boolean wasNew) {}
    public record OrderPaymentResult(OrderResponse order, boolean paymentSucceeded) {}

    // ---- Direct API: caller supplies items explicitly, no cart involved ----
    @Transactional
    public OrderCreationResult createOrder(
            Long userId, CreateOrderRequest req, String bearerToken, String idempotencyKey
    ) {
        Optional<OrderCreationResult> existing = existingOrder(userId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<CartClient.ItemLine> lines = req.getItems().stream()
                .map(i -> new CartClient.ItemLine(i.getProductId(), i.getQuantity()))
                .toList();

        return placeOrder(userId, lines, req.getCouponCode(), req.getAddressId(), bearerToken, idempotencyKey);
    }

    // ---- Cart-driven checkout: pulls the cart's contents from cart-svc,
    // places the order the same way, then clears the cart. ----
    @Transactional
    public OrderCreationResult checkoutFromCart(Long userId, Long addressId, String bearerToken, String idempotencyKey) {
        Optional<OrderCreationResult> existing = existingOrder(userId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        CartClient.Handoff handoff = clients.cart().handoff(bearerToken);
        if (handoff == null) {
            throw new IllegalStateException("cart-svc returned a null checkout handoff");
        }

        OrderCreationResult result = placeOrder(
                userId, handoff.items(), handoff.couponCode(), addressId, bearerToken, idempotencyKey);

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
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return repo.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(order -> new OrderCreationResult(toResponse(order), false));
    }

    private OrderCreationResult placeOrder(
            Long userId, List<CartClient.ItemLine> items, String couponCode, Long addressId,
            String bearerToken, String idempotencyKey
    ) {

        List<ReservedItem> reserved = new ArrayList<>();
        String committedCouponCode = null;

        try {
            BigDecimal subtotal = BigDecimal.ZERO;
            for (CartClient.ItemLine line : items) {
                CatalogClient.ProductDto product = clients.catalog().fetch(line.productId(), bearerToken);
                clients.inventory().adjust(line.productId(), -line.quantity());
                reserved.add(new ReservedItem(product.id(), product.name(), product.price(), line.quantity()));
                subtotal = subtotal.add(product.price().multiply(BigDecimal.valueOf(line.quantity())));
            }

            BigDecimal discount = BigDecimal.ZERO;
            if (couponCode != null && !couponCode.isBlank()) {
                PromotionsClient.DiscountDto d = clients.promotions().commit(couponCode, subtotal, bearerToken);
                discount = d.discountAmount();
                committedCouponCode = d.code();
            }

            Order order = new Order();
            order.setUserId(userId);
            order.setStatus(OrderStatus.PENDING_PAYMENT);
            order.setIdempotencyKey(idempotencyKey);
            for (ReservedItem r : reserved) {
                order.addItem(new OrderItem(r.productId(), r.productName(), r.quantity(), r.price()));
            }
            if (committedCouponCode != null) {
                order.setAppliedCouponCode(committedCouponCode);
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

            Order saved = repo.save(order);
            return new OrderCreationResult(toResponse(saved), true);

        } catch (RuntimeException failure) {
            // Unwinds EVERYTHING committed above this point — reserved
            // stock AND a committed coupon redemption, whichever of them
            // actually happened before the failure. This is the fix for the
            // orphaned-reservation gap the original audit found: previously
            // only the reservation loop was covered, so a failure in the
            // order-save step itself (e.g. the concurrent idempotency-key
            // race below) left committed side effects with nothing to show
            // for them.
            compensate(reserved, committedCouponCode, bearerToken);
            throw failure;
        }
    }

    @Transactional
    public OrderPaymentResult payOrder(Long orderId, Long userId, String role, PayOrderRequest req, String bearerToken, String userEmail) {
        Order order = repo.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        assertCanAccess(order, userId, role);

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new InvalidOrderStateException(
                    "Order " + orderId + " is not awaiting payment (current status: " + order.getStatus() + ")");
        }

        PaymentClient.PaymentOutcome payment = clients.payment().process(orderId, userId, order.getTotalAmount(), req);

        if (payment.succeeded()) {
            order.setStatus(OrderStatus.CONFIRMED);
            order.setPaymentMethod(req.getMethod());
            order.setPaymentReference(payment.reference());
            order.setCustomerEmail(userEmail);
            // COD never actually captured anything — the note should say
            // so, not claim a payment happened that didn't (see
            // PaymentSvc#processCod on the payment-svc side for why
            // COD_PENDING still counts as payment.succeeded() here: the
            // order IS confirmed the moment COD is chosen, same as real
            // storefronts, even though no money has moved yet).
            boolean isCod = "COD_PENDING".equals(payment.status());
            order.addStatusEvent(OrderStatus.CONFIRMED,
                    isCod ? "Order confirmed — pay on delivery" : "Payment confirmed");
        } else {
            releaseOrderSideEffects(order, bearerToken, "payment-failed-order-" + orderId);
            order.setStatus(OrderStatus.CANCELLED);
            order.addStatusEvent(OrderStatus.CANCELLED, "Payment declined");
        }

        Order saved = repo.save(order);
        if (payment.succeeded()) {
            eventPublisher.publishEvent(new OrderConfirmedEvent(
                    saved.getId(), userEmail, toEventItems(saved), saved.getTotalAmount()));
        }
        return new OrderPaymentResult(toResponse(saved), payment.succeeded());
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
        if (idempotencyKey == null) return Optional.empty();
        return repo.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PagedResponse<OrderResponse> listOrders(Long userId, String role, Pageable pageable) {
        Page<Order> page = "ADMIN".equalsIgnoreCase(role)
                ? repo.findAll(pageable)
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
                .map(e -> new TrackingEventResponse(e.getStatus(), e.getNote(), e.getCreatedAt()))
                .toList();
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
            throw new InvalidOrderStateException(
                    "No invoice available for order " + id + " — it was never paid for");
        }

        List<InvoiceLineResponse> lines = order.getItems().stream()
                .map(i -> new InvoiceLineResponse(i.getProductName(), i.getQuantity(), i.getUnitPrice(), i.getSubtotal()))
                .toList();
        BigDecimal itemsSubtotal = lines.stream().map(InvoiceLineResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

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
    public OrderResponse cancelOrder(Long id, Long userId, String role, String bearerToken, String userEmail) {
        Order order = repo.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        assertCanAccess(order, userId, role);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            return toResponse(order);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException(
                    "Order " + id + " can no longer be cancelled (current status: " + order.getStatus() + ")");
        }

        if (order.getStatus() == OrderStatus.CONFIRMED
                && order.getPaymentMethod() != null
                && order.getPaymentMethod() != PaymentMethod.COD) {
            try {
                clients.refund().refund(order.getId(), order.getTotalAmount());
            } catch (RuntimeException e) {
                throw new RefundFailedException(
                        "Refund failed for cancelled order " + id);
            }
        }

        releaseOrderSideEffects(order, bearerToken, "cancel-order-" + id);
        order.setStatus(OrderStatus.CANCELLED);
        boolean isOwnCancellation = order.getUserId() != null && order.getUserId().equals(userId);
        order.addStatusEvent(OrderStatus.CANCELLED, isOwnCancellation ? "Cancelled by customer" : "Cancelled by admin");

        Order saved = repo.save(order);
        eventPublisher.publishEvent(new OrderCancelledEvent(saved.getId(), userEmail));
        return toResponse(saved);
    }

    private void releaseOrderSideEffects(Order order, String bearerToken, String outboxReason) {
        List<ReservedItem> asReserved = order.getItems().stream()
                .map(i -> new ReservedItem(i.getProductId(), i.getProductName(), i.getUnitPrice(), i.getQuantity()))
                .toList();
        compensateWithReason(asReserved, order.getAppliedCouponCode(), bearerToken, outboxReason);
    }

    private void compensate(List<ReservedItem> reserved, String couponCode, String bearerToken) {
        compensateWithReason(reserved, couponCode, bearerToken, "compensate-failed-order-creation");
    }

    private void compensateWithReason(List<ReservedItem> reserved, String couponCode, String bearerToken, String reason) {
        for (ReservedItem r : reserved) {
            try {
                clients.inventory().adjust(r.productId(), r.quantity());
            } catch (RuntimeException compensationError) {
                log.warn("Live stock-release failed for product {} ({}), queuing to outbox: {}",
                        r.productId(), reason, compensationError.getMessage());
                outboxRepo.save(CompensationOutbox.releaseStock(r.productId(), r.quantity(), reason));
            }
        }
        if (couponCode != null) {
            try {
                clients.promotions().release(couponCode, bearerToken);
            } catch (RuntimeException compensationError) {
                log.warn("Live coupon-release failed for {} ({}), queuing to outbox: {}",
                        couponCode, reason, compensationError.getMessage());
                outboxRepo.save(CompensationOutbox.releaseCoupon(couponCode, reason));
            }
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
        return order.getItems().stream()
                .map(i -> new OrderItemEventData(i.getProductName(), i.getQuantity(), i.getUnitPrice(), i.getSubtotal()))
                .toList();
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> new OrderItemResponse(
                        i.getProductId(), i.getProductName(), i.getQuantity(), i.getUnitPrice(), i.getSubtotal()))
                .toList();
        ShippingAddressSummary shippingAddress = order.getShippingLine1() == null ? null
                : new ShippingAddressSummary(
                        order.getShippingLabel(), order.getShippingLine1(), order.getShippingLine2(),
                        order.getShippingCity(), order.getShippingState(),
                        order.getShippingPincode(), order.getShippingPhone());
        OrderResponse response = new OrderResponse(
                order.getId(), order.getUserId(), order.getStatus(), order.getTotalAmount(),
                order.getCreatedAt(), items);
        response.setAppliedCouponCode(order.getAppliedCouponCode());
        response.setDiscountAmount(order.getDiscountAmount());
        response.setShippingAddress(shippingAddress);
        return response;
    }
}