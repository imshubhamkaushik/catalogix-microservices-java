ALTER TABLE orders ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_user_idempotency ON orders (user_id, idempotency_key);
