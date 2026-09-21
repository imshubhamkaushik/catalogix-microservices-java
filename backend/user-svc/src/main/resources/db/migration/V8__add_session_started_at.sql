-- Absolute session lifetime.
--
-- Refresh tokens rotate on every use and each new token got a fresh full expiry, so
-- an active session never expired. session_started_at is copied unchanged from a
-- token to its replacement, so RefreshTokenService can end a session a fixed
-- time after the original sign-in (REFRESH_SESSION_MAX_MS) no matter how often it
-- was refreshed.

ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS session_started_at TIMESTAMPTZ;

UPDATE refresh_tokens SET session_started_at = created_at WHERE session_started_at IS NULL;
