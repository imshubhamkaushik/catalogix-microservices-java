CREATE TABLE IF NOT EXISTS coupon_redemptions (
    operation_id     VARCHAR(160) PRIMARY KEY,
    coupon_code     VARCHAR(50) NOT NULL,
    discount_amount NUMERIC(12,2) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_coupon_redemptions_coupon_code
    ON coupon_redemptions (coupon_code);
