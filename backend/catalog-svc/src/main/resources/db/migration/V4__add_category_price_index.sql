-- Supports the common category + price-range listing query.
-- category is compared case-insensitively in ProductRepository, so index the
-- same normalized expression used by the query rather than relying only on
-- the plain category index.
CREATE INDEX IF NOT EXISTS idx_products_lower_category_price
    ON products (LOWER(category), price);
