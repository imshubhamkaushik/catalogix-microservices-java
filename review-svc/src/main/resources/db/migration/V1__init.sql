CREATE TABLE IF NOT EXISTS reviews (
    id                BIGSERIAL PRIMARY KEY,
    product_id        BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    reviewer_email    VARCHAR(255) NOT NULL,
    rating            INTEGER      NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title             VARCHAR(120),
    body              VARCHAR(2000),
    verified_purchase BOOLEAN      NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_reviews_user_product UNIQUE (user_id, product_id)
);

CREATE INDEX IF NOT EXISTS idx_reviews_product_id ON reviews (product_id);
CREATE INDEX IF NOT EXISTS idx_reviews_user_id ON reviews (user_id);
