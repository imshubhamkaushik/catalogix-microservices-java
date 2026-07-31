package com.catalogix.user.dto;

// Returned by /users/register, /users/login and /users/refresh.
// accessToken is the short-lived JWT sent as "Authorization: Bearer <token>"
// on every request; refreshToken is the long-lived opaque token used only
// against POST /users/refresh to get a new accessToken when it expires.
public class AuthResponse {

    private String accessToken;
    private long accessTokenExpiresInMs;
    private String refreshToken;
    private UserResponse user;

    public AuthResponse() {}

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

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public UserResponse getUser() { return user; }
    public void setUser(UserResponse user) { this.user = user; }
}
