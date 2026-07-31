package com.catalogix.order.svc;

import com.catalogix.order.client.ProductSvcClient;
import com.catalogix.order.model.OutboxStatus;
import com.catalogix.order.model.StockAdjustmentOutbox;
import com.catalogix.order.repository.StockAdjustmentOutboxRepository;
import com.catalogix.order.security.JwtService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Periodically retries stock adjustments that couldn't be delivered to
 * product-svc live (see OrderSvc.compensate() and cancelOrder()). Runs as a
 * background job with no inbound user request, so it authenticates to
 * product-svc using a short-lived system token (see JwtService.generateSystemToken)
 * rather than forwarding anyone's session.
 */
@Component
public class StockAdjustmentOutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(StockAdjustmentOutboxProcessor.class);
    private static final int MAX_ATTEMPTS = 10;

    private final StockAdjustmentOutboxRepository repo;
    private final ProductSvcClient productSvcClient;
    private final JwtService jwtService;

    public StockAdjustmentOutboxProcessor(
            StockAdjustmentOutboxRepository repo,
            ProductSvcClient productSvcClient,
            JwtService jwtService
    ) {
        this.repo = repo;
        this.productSvcClient = productSvcClient;
        this.jwtService = jwtService;
    }

    @Scheduled(fixedDelayString = "${OUTBOX_POLL_INTERVAL_MS:30000}")
    public void processPending() {
        List<StockAdjustmentOutbox> batch = repo.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (batch.isEmpty()) {
            return;
        }

        String bearerToken = "Bearer " + jwtService.generateSystemToken();
        for (StockAdjustmentOutbox entry : batch) {
            attempt(entry, bearerToken);
        }
    }

    // Deliberately not @Transactional: each branch does exactly one repo.save(),
    // and Spring Data's CrudRepository.save() is already transactional on its
    // own. (An explicit @Transactional here wouldn't even take effect anyway —
    // this method is called via `this.attempt(...)` from within the same bean,
    // which bypasses the AOP proxy that @Transactional relies on.)
    private void attempt(StockAdjustmentOutbox entry, String bearerToken) {
        try {
            productSvcClient.adjustStock(entry.getProductId(), entry.getDelta(), bearerToken);
            entry.setStatus(OutboxStatus.COMPLETED);
            entry.setUpdatedAt(Instant.now());
            repo.save(entry);
        } catch (RuntimeException e) {
            entry.setAttempts(entry.getAttempts() + 1);
            entry.setLastError(truncate(e.getMessage()));
            entry.setUpdatedAt(Instant.now());
            if (entry.getAttempts() >= MAX_ATTEMPTS) {
                entry.setStatus(OutboxStatus.DEAD_LETTER);
                log.error("Outbox entry {} exhausted {} retries and was moved to DEAD_LETTER "
                                + "(product={}, delta={}) — needs manual attention.",
                        entry.getId(), MAX_ATTEMPTS, entry.getProductId(), entry.getDelta());
            }
            repo.save(entry);
        }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
