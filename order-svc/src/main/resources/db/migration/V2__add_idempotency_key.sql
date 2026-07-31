ALTER TABLE orders ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

-- Postgres treats NULLs as distinct in a unique index (standard SQL semantics),
-- so orders placed without an Idempotency-Key header are unaffected — only a
-- repeated (user_id, idempotency_key) pair is rejected as a duplicate.
CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_user_idempotency ON orders (user_id, idempotency_key);
