CREATE TABLE IF NOT EXISTS refunds (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT       NOT NULL,
    original_payment_id BIGINT       NOT NULL REFERENCES payments (id),
    amount              NUMERIC(12, 2) NOT NULL,
    reference           VARCHAR(100),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_refunds_order_id ON refunds (order_id);
