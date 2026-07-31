ALTER TABLE users ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT false;

-- Both token tables mirror refresh_tokens: only a SHA-256 hash is ever stored,
-- the raw token exists only in the email sent to the user and is never persisted.

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_evt_user_id ON email_verification_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_evt_token_hash ON email_verification_tokens (token_hash);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_prt_user_id ON password_reset_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_prt_token_hash ON password_reset_tokens (token_hash);
