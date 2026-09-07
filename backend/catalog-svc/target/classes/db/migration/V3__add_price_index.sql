-- Supports the minPrice/maxPrice range filter added to GET /products
-- (see ProductRepository#search). category and owner_id already have
-- indexes from V2; price didn't need one until range filtering existed.
CREATE INDEX IF NOT EXISTS idx_products_price ON products (price);
