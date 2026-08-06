CREATE TABLE IF NOT EXISTS coupons (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(50) NOT NULL UNIQUE,
    discount_type  VARCHAR(20) NOT NULL,
    discount_value NUMERIC(12,2) NOT NULL,
    max_uses       INTEGER,
    used_count     INTEGER NOT NULL DEFAULT 0,
    expires_at     TIMESTAMPTZ,
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
