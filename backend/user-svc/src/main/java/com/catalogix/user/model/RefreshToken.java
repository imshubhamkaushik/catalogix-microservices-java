package com.catalogix.user.model;

import jakarta.persistence.*;
import java.time.Instant;

// Stores only a SHA-256 hash of the token, never the raw value — mirrors how
// we never store raw passwords. The raw token is shown to the client exactly
// once, at issuance.
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // Both added in V6, for the session-list UI (see UserController's
    // /users/me/sessions endpoints). Nullable at the column level because
    // existing rows predate this migration — backfilled once, not enforced
    // NOT NULL, since a token issued by an old build before this deploy
    // wouldn't have a user agent to backfill.
    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    // When the ORIGINAL sign-in of this session happened. Copied unchanged on every
    // rotation (V8), so a session has an absolute maximum age. Null only on rows
    // created before V8 that the migration could not backfill.
    @Column(name = "session_started_at")
    private Instant sessionStartedAt;

    public RefreshToken() {
    }

    public RefreshToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public RefreshToken(Long userId, String tokenHash, Instant expiresAt, String userAgent) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.lastUsedAt = Instant.now();
    }

    public Instant getSessionStartedAt() {
        return sessionStartedAt;
    }
    public void setSessionStartedAt(Instant sessionStartedAt) {
        this.sessionStartedAt = sessionStartedAt;
    }

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }
    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }
    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getUserAgent() {
        return userAgent;
    }
    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public boolean isValid(Instant now) {
        return !revoked && expiresAt.isAfter(now);
    }
}
