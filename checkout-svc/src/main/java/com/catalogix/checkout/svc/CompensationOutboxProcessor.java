package com.catalogix.checkout.svc;

import com.catalogix.checkout.client.InventoryClient;
import com.catalogix.checkout.client.PromotionsClient;
import com.catalogix.checkout.model.CompensationOutbox;
import com.catalogix.checkout.model.OutboxStatus;
import com.catalogix.checkout.repository.CompensationOutboxRepository;
import com.catalogix.checkout.security.JwtService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Retries compensating actions (release stock, release a coupon use) that
 * failed on their live/synchronous attempt. Generalized from the original
 * stock-only outbox processor — same retry/dead-letter shape, now dispatched
 * by CompensationType instead of assuming every row is a stock adjustment.
 */
@Service
public class CompensationOutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(CompensationOutboxProcessor.class);
    private static final int MAX_ATTEMPTS = 5;

    private final CompensationOutboxRepository outboxRepo;
    private final InventoryClient inventoryClient;
    private final PromotionsClient promotionsClient;
    private final JwtService jwtService;

    public CompensationOutboxProcessor(CompensationOutboxRepository outboxRepo, InventoryClient inventoryClient,
                                        PromotionsClient promotionsClient, JwtService jwtService) {
        this.outboxRepo = outboxRepo;
        this.inventoryClient = inventoryClient;
        this.promotionsClient = promotionsClient;
        this.jwtService = jwtService;
    }

    @Scheduled(fixedDelayString = "${OUTBOX_POLL_INTERVAL_MS:5000}")
    @Transactional
    public void processPending() {
        // claimPendingBatch() row-locks (FOR UPDATE SKIP LOCKED) everything
        // it returns for the life of this transaction — see the repository
        // Javadoc for why that matters once this service has >1 replica.
        List<CompensationOutbox> batch = outboxRepo.claimPendingBatch();

        for (CompensationOutbox entry : batch) {
            // Minted fresh per entry rather than once for the whole batch:
            // cheap (local signing, no network call) and closes the edge
            // case where a slow batch outlives a single token's TTL.
            String bearerToken = "Bearer " + jwtService.generateSystemToken();
            try {
                switch (entry.getType()) {
                    case RELEASE_STOCK -> inventoryClient.adjust(entry.getProductId(), entry.getDelta(), bearerToken);
                    case RELEASE_COUPON -> promotionsClient.release(entry.getCouponCode(), bearerToken);
                }
                entry.setStatus(OutboxStatus.COMPLETED);
            } catch (RuntimeException e) {
                entry.setAttempts(entry.getAttempts() + 1);
                entry.setLastError(e.getMessage());
                if (entry.getAttempts() >= MAX_ATTEMPTS) {
                    entry.setStatus(OutboxStatus.DEAD_LETTER);
                    log.error("Compensation outbox entry {} exhausted retries ({}): {}",
                            entry.getId(), entry.getType(), e.getMessage());
                } else {
                    log.warn("Compensation outbox entry {} attempt {} failed ({}): {}",
                            entry.getId(), entry.getAttempts(), entry.getType(), e.getMessage());
                }
            }
            outboxRepo.save(entry);
        }
    }
}
