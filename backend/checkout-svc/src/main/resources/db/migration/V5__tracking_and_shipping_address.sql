CREATE TABLE IF NOT EXISTS order_status_events (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    status      VARCHAR(20)  NOT NULL,
    note        VARCHAR(200),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_order_status_events_order_id ON order_status_events (order_id);

-- Shipping address snapshot — see Order's Javadoc. All nullable: existing
-- rows and any order placed without an addressId simply have no snapshot.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_label    VARCHAR(40);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_line1    VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_line2    VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_city     VARCHAR(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_state    VARCHAR(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_pincode  VARCHAR(12);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_phone    VARCHAR(20);
