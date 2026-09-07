package com.catalogix.checkout.svc;

import com.catalogix.checkout.model.OutboxStatus;
import com.catalogix.checkout.repository.CompensationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Registers a self-updating gauge for the outbox's DEAD_LETTER count —
 * rows the scheduled CompensationOutboxProcessor gave up retrying, which
 * need manual attention (a stuck refund/stock-release/coupon-restore that
 * never completed). Before this, the only way to see this number was a
 * manual GET /admin/outbox call (see AdminController) — invisible to
 * Prometheus/Grafana/Alertmanager, so a growing backlog of failed
 * compensations could sit unnoticed indefinitely.
 *
 * A Gauge (not a Counter) is the correct metric type here: this is "how
 * many rows are CURRENTLY in this state" — a value that can go up AND
 * down (an operator manually resolving a dead-lettered row should make
 * this number drop) — not a monotonically-increasing count of events.
 * Gauge.builder's supplier function is called live at every Prometheus
 * scrape, so this always reflects the real current count, not a snapshot
 * taken once at startup.
 */
@Component
public class OutboxMetrics {

    private final CompensationOutboxRepository outboxRepository;
    private final MeterRegistry meterRegistry;

    public OutboxMetrics(CompensationOutboxRepository outboxRepository, MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("checkout_outbox_dead_letter_count",
                        outboxRepository,
                        repo -> repo.countByStatus(OutboxStatus.DEAD_LETTER))
                .description("Number of compensation-outbox entries stuck in DEAD_LETTER — exceeded max retry attempts and need manual attention")
                .register(meterRegistry);

        // Pending count is also worth exposing alongside dead-letter — a
        // growing PENDING backlog (the processor falling behind, not yet
        // failing) is an earlier warning sign than waiting for entries to
        // actually reach DEAD_LETTER.
        Gauge.builder("checkout_outbox_pending_count",
                        outboxRepository,
                        repo -> repo.countByStatus(OutboxStatus.PENDING))
                .description("Number of compensation-outbox entries still PENDING processing")
                .register(meterRegistry);
    }
}
