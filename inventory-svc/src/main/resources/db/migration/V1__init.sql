CREATE TABLE IF NOT EXISTS inventory_items (
    product_id  BIGINT PRIMARY KEY,
    quantity    INTEGER NOT NULL CHECK (quantity >= 0),
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
