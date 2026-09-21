-- Idempotent refunds.
-- checkout-svc sends an Idempotency-Key with each refund (e.g. "cancel-order-<id>"). Replaying it
-- returns the refund already made instead of failing or refunding again — so a cancellation that
-- refunded successfully but then failed locally can simply be retried.
ALTER TABLE refunds ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_refunds_order_idempotency_key
    ON refunds (order_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
