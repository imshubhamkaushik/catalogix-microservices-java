CREATE TABLE IF NOT EXISTS stock_adjustment_outbox (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT       NOT NULL,
    delta       INTEGER      NOT NULL,
    reason      VARCHAR(255),
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts    INTEGER      NOT NULL DEFAULT 0,
    last_error  VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_outbox_status ON stock_adjustment_outbox (status);
