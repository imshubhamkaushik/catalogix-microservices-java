CREATE TABLE IF NOT EXISTS shipments(
 id BIGSERIAL PRIMARY KEY,order_id BIGINT NOT NULL,user_id BIGINT NOT NULL,seller_id BIGINT NOT NULL,tracking_number VARCHAR(80) NOT NULL UNIQUE,status VARCHAR(30) NOT NULL DEFAULT 'CREATED',created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),CONSTRAINT uk_order_seller UNIQUE(order_id,seller_id)
);
CREATE INDEX IF NOT EXISTS idx_shipments_order ON shipments(order_id);
CREATE INDEX IF NOT EXISTS idx_shipments_seller ON shipments(seller_id);
