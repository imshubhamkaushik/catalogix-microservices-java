package com.catalogix.user.dto;

import java.time.Instant;

// Deliberately never includes the token hash — this DTO exists so a user
// can see and revoke their own sessions, not so anything downstream of the
// API could reconstruct or compare against a real token.
public class SessionResponse {

    private Long id;
    private String userAgent;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant expiresAt;
    // True for the session tied to the refresh-token cookie making THIS
    // request. Lets the UI show "This device" and disable revoking it here
    // (the "Log out" button already exists for that, and revoking your own
    // current session out from under yourself mid-request is confusing UX,
    // not a security requirement).
    private boolean current;

    public SessionResponse() {}

    public SessionResponse(Long id, String userAgent, Instant createdAt, Instant lastUsedAt,
                            Instant expiresAt, boolean current) {
        this.id = id;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.expiresAt = expiresAt;
        this.current = current;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
}
