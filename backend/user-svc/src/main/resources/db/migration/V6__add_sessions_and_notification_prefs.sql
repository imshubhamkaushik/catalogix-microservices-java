-- Adds the columns needed for two features:
--   1. Individual session listing/revocation — refresh_tokens previously had
--      no way to tell one session apart from another besides created_at.
--      user_agent lets the UI show "Chrome on macOS" instead of an opaque
--      row; last_used_at lets it show "active 2 minutes ago" vs. "never
--      used since issued", which matters for deciding what's safe to revoke.
--   2. Notification preferences — self-service opt-out, stored per user.
--      NOT currently read by notification-svc (see UserSvc/UserController
--      Javadoc on the preference endpoints for why enforcement is a
--      deliberate follow-up, not part of this migration).

ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS user_agent   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ;

-- Backfill existing rows so last_used_at is never null for tokens issued
-- before this migration — falls back to created_at, the best available
-- approximation of "last used" for a token nobody has rotated yet.
UPDATE refresh_tokens SET last_used_at = created_at WHERE last_used_at IS NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS order_emails_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS promo_emails_enabled BOOLEAN NOT NULL DEFAULT true;
