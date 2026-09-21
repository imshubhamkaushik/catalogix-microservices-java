-- Idempotency ledger for stock adjustments.
--
-- checkout-svc sends an operation id with every reserve / release. Recording each
-- id here (inside the same transaction as the stock change) makes a repeated call a
-- no-op instead of a second adjustment — so a retry after a timeout, or a
-- compensation outbox entry replayed after a crash, can never double-release stock.
--
-- A delta of 0 marks a "cancelled" reservation: a release arrived for a reservation
-- that was never recorded (its request timed out and may still arrive late). Storing
-- the tombstone makes a late-arriving original a no-op too.

CREATE TABLE IF NOT EXISTS inventory_operations (
    operation_id VARCHAR(120) PRIMARY KEY,
    product_id   BIGINT       NOT NULL,
    delta        INTEGER      NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_inventory_operations_created_at ON inventory_operations (created_at);
