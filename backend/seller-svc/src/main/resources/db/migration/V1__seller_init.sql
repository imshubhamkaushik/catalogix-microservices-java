CREATE TABLE IF NOT EXISTS seller_profiles(
 id BIGSERIAL PRIMARY KEY,user_id BIGINT NOT NULL,display_name VARCHAR(120) NOT NULL,business_name VARCHAR(160),description VARCHAR(500),status VARCHAR(20) NOT NULL DEFAULT 'PENDING',commission_rate NUMERIC(5,2) NOT NULL DEFAULT 8.00,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),CONSTRAINT uk_seller_user UNIQUE(user_id)
);
CREATE TABLE IF NOT EXISTS seller_ledger(
 id BIGSERIAL PRIMARY KEY,seller_user_id BIGINT NOT NULL,type VARCHAR(30) NOT NULL,amount NUMERIC(12,2) NOT NULL,source_key VARCHAR(160) NOT NULL UNIQUE,description VARCHAR(300),created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_seller_ledger_user ON seller_ledger(seller_user_id);
CREATE TABLE IF NOT EXISTS seller_payouts(
 id BIGSERIAL PRIMARY KEY,seller_user_id BIGINT NOT NULL,amount NUMERIC(12,2) NOT NULL,status VARCHAR(20) NOT NULL,reference VARCHAR(80) NOT NULL UNIQUE,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_payout_user ON seller_payouts(seller_user_id);
