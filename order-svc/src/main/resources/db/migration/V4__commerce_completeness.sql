-- Payments: one mock payment attempt per order (see Payment/PaymentSvc).
CREATE TABLE IF NOT EXISTS payments (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    amount      NUMERIC(12,2) NOT NULL,
    method      VARCHAR(50)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    reference   VARCHAR(100),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_payments_order_id ON payments (order_id);

-- Coupons: simple percentage/fixed-amount discounts, admin-managed.
CREATE TABLE IF NOT EXISTS coupons (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(50)   NOT NULL UNIQUE,
    discount_type   VARCHAR(20)   NOT NULL,
    discount_value  NUMERIC(12,2) NOT NULL,
    max_uses        INTEGER,
    used_count      INTEGER       NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ,
    active          BOOLEAN       NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Server-side cart: one row per user (enforced by the UNIQUE constraint), so
-- it survives a page refresh / different device, unlike the old client-only cart.
CREATE TABLE IF NOT EXISTS carts (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL UNIQUE,
    coupon_code VARCHAR(50),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS cart_items (
    id          BIGSERIAL PRIMARY KEY,
    cart_id     BIGINT       NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    product_id  BIGINT       NOT NULL,
    quantity    INTEGER      NOT NULL,
    added_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (cart_id, product_id)
);

-- Orders gain coupon bookkeeping (the discount is snapshotted at checkout
-- time, same reasoning as OrderItem's price snapshot).
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS applied_coupon_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
