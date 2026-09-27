ALTER TABLE products ADD COLUMN IF NOT EXISTS moderation_status VARCHAR(30) NOT NULL DEFAULT 'PUBLISHED';
CREATE INDEX IF NOT EXISTS idx_products_moderation_status ON products(moderation_status);
