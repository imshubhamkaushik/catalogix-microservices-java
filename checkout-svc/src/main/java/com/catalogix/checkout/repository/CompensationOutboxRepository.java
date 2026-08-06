package com.catalogix.checkout.repository;

import com.catalogix.checkout.model.CompensationOutbox;
import com.catalogix.checkout.model.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CompensationOutboxRepository extends JpaRepository<CompensationOutbox, Long> {

    List<CompensationOutbox> findByStatusInOrderByCreatedAtDesc(List<OutboxStatus> statuses);

    /**
     * FOR UPDATE SKIP LOCKED is the actual fix for a real gap the original
     * audit found: the old processor had no claiming step, so if checkout-svc
     * were ever scaled to multiple replicas, each one would independently
     * pick up and process the same pending rows, double-releasing stock or
     * coupon uses. Locking (and skipping already-locked) rows here means two
     * replicas racing to run the scheduled job at the same moment simply
     * split the batch between them instead of duplicating it — each row is
     * claimed by exactly one replica's transaction.
     */
    @Query(value = """
        SELECT * FROM compensation_outbox
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT 50
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<CompensationOutbox> claimPendingBatch();
}
