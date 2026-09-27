package com.catalogix.checkout.svc;

import com.catalogix.checkout.model.CompensationOutbox;
import com.catalogix.checkout.repository.CompensationOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists compensation intent with an explicit transaction boundary chosen
 * by the caller: enqueue() participates in the current transaction, while
 * enqueueIndependent() commits in a separate transaction.
 *
 * Checkout can fail after a remote side effect has already committed. In
 * those paths the fallback outbox INSERT must survive the local rollback, so
 * enqueueIndependent() uses REQUIRES_NEW. Other flows (such as cancelling an
 * existing order) use enqueue() so their local state change and compensation
 * intent commit atomically.
 */
@Service
public class CompensationOutboxWriter {

    private final CompensationOutboxRepository repository;

    public CompensationOutboxWriter(CompensationOutboxRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes inside the caller's transaction. Use this when the compensation
     * intent must commit or roll back together with the local state change
     * that makes that compensation necessary (for example order cancellation).
     */
    @Transactional
    public CompensationOutbox enqueue(CompensationOutbox entry) {
        return repository.save(entry);
    }

    /**
     * Writes independently of the caller's transaction. Use this only when a
     * remote side effect may already have committed but the local transaction
     * is expected to roll back (for example a failed order creation or a
     * successful refund followed by a failed restock).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompensationOutbox enqueueIndependent(CompensationOutbox entry) {
        return repository.save(entry);
    }
}
