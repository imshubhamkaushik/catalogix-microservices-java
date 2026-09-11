package com.catalogix.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

// Returned by /users/register, /users/login and /users/refresh.
// accessToken is the short-lived JWT sent as "Authorization: Bearer <token>" on every request. 
// refreshToken is the long-lived opaque token — 
// it is intentionally @JsonIgnore'd below and NEVER appears in the JSON body; UserController reads it here in Java only to set it as an httpOnly Set-Cookie header. 
// Keeping a long-lived credential out of JS-readable response bodies (and therefore out of localStorage/sessionStorage, wherever
// the frontend stores the rest of this response) means an XSS bug in the SPA
// can no longer exfiltrate a portable, long-lived credential — at worst it can abuse the live session while a tab is open, not carry it away.
public class AuthResponse {

    private String accessToken;
    private long accessTokenExpiresInMs;
    private String refreshToken;
    private UserResponse user;

    public AuthResponse() {
        /*
         * Required by Jackson to instantiate this DTO during JSON deserialization.
         * Fields are populated through the setters after construction.
         */
    }

    public AuthResponse(String accessToken, long accessTokenExpiresInMs, String refreshToken, UserResponse user) {
        this.accessToken = accessToken;
        this.accessTokenExpiresInMs = accessTokenExpiresInMs;
        this.refreshToken = refreshToken;
        this.user = user;
    }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public long getAccessTokenExpiresInMs() { return accessTokenExpiresInMs; }
    public void setAccessTokenExpiresInMs(long accessTokenExpiresInMs) { this.accessTokenExpiresInMs = accessTokenExpiresInMs; }

    @JsonIgnore
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public UserResponse getUser() { return user; }
    public void setUser(UserResponse user) { this.user = user; }
}
