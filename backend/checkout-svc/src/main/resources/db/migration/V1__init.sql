CREATE TABLE IF NOT EXISTS orders (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    -- Was DEFAULT 'PENDING' in the original schema, which doesn't match any
    -- OrderStatus enum constant (the real default is PENDING_PAYMENT, always
    -- set explicitly in code). Harmless as long as every insert goes through
    -- CheckoutSvc, but a landmine for any future manual/raw insert — fixed
    -- here so the column's own metadata is honest about what it stores.
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING_PAYMENT',
    total_amount  NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS order_items (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id    BIGINT       NOT NULL,
    product_name  VARCHAR(255) NOT NULL,
    quantity      INTEGER      NOT NULL,
    unit_price    NUMERIC(10,2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders (user_id);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items (order_id);
