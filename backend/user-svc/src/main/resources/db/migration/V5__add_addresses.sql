CREATE TABLE IF NOT EXISTS addresses (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label       VARCHAR(40)  NOT NULL,
    line1       VARCHAR(200) NOT NULL,
    line2       VARCHAR(200),
    city        VARCHAR(100) NOT NULL,
    state       VARCHAR(100) NOT NULL,
    pincode     VARCHAR(12)  NOT NULL,
    phone       VARCHAR(20)  NOT NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_addresses_user_id ON addresses (user_id);

-- Deliberately NOT a partial unique index on (user_id) WHERE is_default —
-- the "exactly one default" invariant is enforced in AddressSvc instead
-- (clear-then-set within the same transaction), which keeps the rule
-- readable in one place instead of split between application code and a
-- constraint that would need its own violation-handling path.
