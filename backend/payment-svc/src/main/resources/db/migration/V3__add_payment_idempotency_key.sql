ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

-- A key is scoped to the originating user. The service additionally verifies
-- orderId/amount/method before replaying, so accidental key reuse cannot
-- mutate or cross-wire another payment operation.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_user_idempotency
    ON payments (requested_by_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
