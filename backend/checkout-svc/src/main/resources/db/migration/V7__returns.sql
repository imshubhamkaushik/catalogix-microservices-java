CREATE TABLE IF NOT EXISTS return_requests (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT       NOT NULL REFERENCES orders (id),
    user_id         BIGINT       NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    refund_amount   NUMERIC(12, 2) NOT NULL,
    decision_note   VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS return_items (
    id                  BIGSERIAL PRIMARY KEY,
    return_request_id   BIGINT       NOT NULL REFERENCES return_requests (id) ON DELETE CASCADE,
    product_id          BIGINT       NOT NULL,
    product_name        VARCHAR(255) NOT NULL,
    quantity            INTEGER      NOT NULL,
    unit_price          NUMERIC(10, 2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_return_requests_order_id ON return_requests (order_id);
CREATE INDEX IF NOT EXISTS idx_return_requests_user_id ON return_requests (user_id);
CREATE INDEX IF NOT EXISTS idx_return_requests_status ON return_requests (status);
CREATE INDEX IF NOT EXISTS idx_return_items_return_request_id ON return_items (return_request_id);
