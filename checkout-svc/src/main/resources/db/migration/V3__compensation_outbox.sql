CREATE TABLE IF NOT EXISTS compensation_outbox (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(20)  NOT NULL,
    product_id  BIGINT,
    delta       INTEGER,
    coupon_code VARCHAR(50),
    reason      VARCHAR(255),
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts    INTEGER      NOT NULL DEFAULT 0,
    last_error  VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_compensation_target CHECK (
        (type = 'RELEASE_STOCK' AND product_id IS NOT NULL AND delta IS NOT NULL AND coupon_code IS NULL)
        OR
        (type = 'RELEASE_COUPON' AND coupon_code IS NOT NULL AND product_id IS NULL AND delta IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_compensation_outbox_status ON compensation_outbox (status);
