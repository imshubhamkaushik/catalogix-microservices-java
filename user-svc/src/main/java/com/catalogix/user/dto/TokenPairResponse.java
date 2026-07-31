package com.catalogix.user.dto;

// Returned by POST /users/refresh. Deliberately lighter than AuthResponse —
// a refresh doesn't change the user's profile, so there's no need to re-send it.
public class TokenPairResponse {
    private String accessToken;
    private long accessTokenExpiresInMs;
    private String refreshToken;

    public TokenPairResponse() {}

    public TokenPairResponse(String accessToken, long accessTokenExpiresInMs, String refreshToken) {
        this.accessToken = accessToken;
        this.accessTokenExpiresInMs = accessTokenExpiresInMs;
        this.refreshToken = refreshToken;
    }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public long getAccessTokenExpiresInMs() { return accessTokenExpiresInMs; }
    public void setAccessTokenExpiresInMs(long accessTokenExpiresInMs) { this.accessTokenExpiresInMs = accessTokenExpiresInMs; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
}
