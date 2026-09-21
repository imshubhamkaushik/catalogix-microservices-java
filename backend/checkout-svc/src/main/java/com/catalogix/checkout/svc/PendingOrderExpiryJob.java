package com.catalogix.checkout.svc;

import com.catalogix.checkout.model.OrderStatus;
import com.catalogix.checkout.repository.OrderRepository;
import com.catalogix.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Releases stock (and coupon uses) held by orders that were placed but never paid.
 *
 * Placing an order reserves stock immediately, but nothing ever gave it back if the customer
 * simply walked away — so abandoned checkouts slowly drained availability. Orders still in
 * PENDING_PAYMENT after ORDER_PAYMENT_TIMEOUT_MINUTES (default 30) are cancelled and their
 * side effects released. Every replica may run the sweep: expireUnpaidOrder row-locks and
 * re-checks each order, so two replicas cannot expire the same one twice.
 */
@Component
public class PendingOrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderExpiryJob.class);

    private final OrderRepository orders;
    private final CheckoutSvc checkoutSvc;
    private final JwtService jwtService;
    private final Duration paymentWindow;
    private final int batchSize;

    public PendingOrderExpiryJob(
            OrderRepository orders,
            CheckoutSvc checkoutSvc,
            JwtService jwtService,
            @Value("${ORDER_PAYMENT_TIMEOUT_MINUTES:30}") long timeoutMinutes,
            @Value("${PENDING_ORDER_SWEEP_BATCH:50}") int batchSize) {
        this.orders = orders;
        this.checkoutSvc = checkoutSvc;
        this.jwtService = jwtService;
        this.paymentWindow = Duration.ofMinutes(timeoutMinutes);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${PENDING_ORDER_SWEEP_MS:60000}")
    public void sweep() {
        Instant cutoff = Instant.now().minus(paymentWindow);
        List<Long> stale = orders.findIdsByStatusCreatedBefore(
                OrderStatus.PENDING_PAYMENT, cutoff, PageRequest.of(0, batchSize));
        for (Long id : stale) {
            try {
                if (checkoutSvc.expireUnpaidOrder(id, "Bearer " + jwtService.generateSystemToken())) {
                    log.info("Expired unpaid order {} (no payment within {})", id, paymentWindow);
                }
            } catch (RuntimeException e) {
                // One bad order must not stop the sweep; it is retried on the next run.
                log.warn("Could not expire unpaid order {}: {}", id, e.getMessage());
            }
        }
    }
}
