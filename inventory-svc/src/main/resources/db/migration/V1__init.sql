CREATE TABLE IF NOT EXISTS stock_items (
    product_id  BIGINT PRIMARY KEY,
    quantity    INTEGER NOT NULL CHECK (quantity >= 0)
);
