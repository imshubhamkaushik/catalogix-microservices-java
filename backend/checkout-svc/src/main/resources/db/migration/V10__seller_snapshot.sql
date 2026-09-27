ALTER TABLE order_items ADD COLUMN IF NOT EXISTS seller_id BIGINT; CREATE INDEX IF NOT EXISTS idx_order_items_seller_id ON order_items(seller_id);
