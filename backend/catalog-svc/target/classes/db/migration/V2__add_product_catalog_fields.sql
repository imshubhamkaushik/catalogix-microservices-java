ALTER TABLE products
    ADD COLUMN IF NOT EXISTS category       VARCHAR(100) NOT NULL DEFAULT 'GENERAL',
    ADD COLUMN IF NOT EXISTS stock_quantity INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS owner_id       BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS created_at     TIMESTAMPTZ  NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_products_category ON products (category);
CREATE INDEX IF NOT EXISTS idx_products_owner_id ON products (owner_id);
