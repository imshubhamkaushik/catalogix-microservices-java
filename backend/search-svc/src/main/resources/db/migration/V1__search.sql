CREATE TABLE IF NOT EXISTS search_products(
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price NUMERIC(12,2) NOT NULL,
    category VARCHAR(120) NOT NULL,
    owner_id BIGINT,
    image_url VARCHAR(500),
    moderation_status VARCHAR(30) NOT NULL DEFAULT 'PUBLISHED',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_search_products_category ON search_products(category);
CREATE INDEX IF NOT EXISTS idx_search_products_status ON search_products(moderation_status);
CREATE INDEX IF NOT EXISTS idx_search_products_price ON search_products(price);
CREATE INDEX IF NOT EXISTS idx_search_products_updated ON search_products(updated_at DESC);
