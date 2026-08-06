CREATE TABLE IF NOT EXISTS payments (
    id                    BIGSERIAL PRIMARY KEY,
    order_id              BIGINT NOT NULL,
    requested_by_user_id  BIGINT NOT NULL,
    amount                NUMERIC(12,2) NOT NULL,
    method                VARCHAR(50) NOT NULL,
    status                VARCHAR(20) NOT NULL,
    reference             VARCHAR(100),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payments_order_id ON payments(order_id);
