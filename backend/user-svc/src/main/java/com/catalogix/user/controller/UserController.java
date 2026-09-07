package com.catalogix.user.controller;

import com.catalogix.user.dto.AuthResponse;
import com.catalogix.user.dto.CreateUserRequest;
import com.catalogix.user.dto.ForgotPasswordRequest;
import com.catalogix.user.dto.LoginRequest;
import com.catalogix.user.dto.ResetPasswordRequest;
import com.catalogix.user.dto.TokenPairResponse;
import com.catalogix.user.dto.UpdateProfileRequest;
import com.catalogix.user.dto.UserResponse;
import com.catalogix.user.exception.ForbiddenException;
import com.catalogix.user.exception.UnauthorizedException;
import com.catalogix.user.security.RefreshTokenService;
import com.catalogix.user.svc.UserSvc;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    // Scoped to /users (not /) so this cookie is never sent to /products,
    // /orders, /cart, etc. — the only endpoints that ever need it are
    // /users/refresh, /users/logout, and /users/logout-all, all under /users.
    private static final String REFRESH_COOKIE_NAME = "catalogix_refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/users";

    private final UserSvc svc;
    private final RefreshTokenService refreshTokenService;

    // Defaults to true (real deployments sit behind TLS at the ALB/gateway).
    // Set REFRESH_COOKIE_SECURE=false only for local docker-compose over
    // plain HTTP — browsers refuse to store a Secure cookie set over a
    // non-HTTPS response, so leaving this true against plain-HTTP compose
    // would silently make login "work" (200 OK) while the browser quietly
    // discards the cookie and refresh never succeeds.
    @Value("${REFRESH_COOKIE_SECURE:true}")
    private boolean cookieSecure;

    public UserController(UserSvc svc, RefreshTokenService refreshTokenService) {
        this.svc = svc;
        this.refreshTokenService = refreshTokenService;
    }

    // Register endpoint: creates a new user, sends a verification email, and
    // immediately returns tokens so the frontend can log the user straight in.
    // The refresh token is never in the JSON body — see AuthResponse's Javadoc.
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody CreateUserRequest req, HttpServletResponse response) {
        AuthResponse created = svc.register(req);
        setRefreshCookie(response, created.getRefreshToken());
        return ResponseEntity.status(201).body(created);
    }

    // Login endpoint: validates credentials and returns tokens + profile on success.
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        AuthResponse result = svc.login(req);
        setRefreshCookie(response, result.getRefreshToken());
        return ResponseEntity.ok(result);
    }

    // Exchanges a still-valid refresh token (read from the httpOnly cookie —
    // never from the request body) for a new access token, rotating the
    // cookie to the newly-issued refresh token in the same response. The old
    // refresh token stops working the moment this succeeds (see
    // RefreshTokenService's rotate-on-use Javadoc).
    @PostMapping("/refresh")
    public ResponseEntity<TokenPairResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException("Missing refresh token cookie");
        }
        TokenPairResponse result = svc.refresh(refreshToken);
        setRefreshCookie(response, result.getRefreshToken());
        return ResponseEntity.ok(result);
    }

    // Logs out this session only, by revoking the refresh token read from the
    // cookie and clearing the cookie itself. The still-live access token
    // remains valid until it naturally expires (it's short-lived by design —
    // see JWT_EXPIRATION_MS). Missing/absent cookie is treated as "already
    // logged out" rather than an error — logout should never fail loudly.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            svc.logout(refreshToken);
        }
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    // Logs out every session for the current account (revokes all refresh
    // tokens) and clears this browser's own cookie too — otherwise this
    // browser would keep a now-revoked-but-still-cookied refresh token that
    // fails the next time it's actually used instead of being cleanly gone.
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutEverywhere(@RequestAttribute("userId") Long userId, HttpServletResponse response) {
        svc.logoutEverywhere(userId);
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    private void setRefreshCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                // Strict, not Lax: the gateway is the SPA's own origin (see
                // gateway/nginx.conf), so there is no legitimate cross-site
                // navigation that needs this cookie sent — Strict is safe
                // and the tighter choice.
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ofMillis(refreshTokenService.getExpirationMs()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    // Clicking the link in the verification email lands here. Always a GET
    // since it's just a plain link, not a form submission.
    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestParam String token) {
        svc.verifyEmail(token);
        return ResponseEntity.noContent().build();
    }

    // For when the original verification email was lost, expired, or never
    // arrived. Requires auth (unlike forgot-password) since it's tied to "my
    // own account," not an arbitrary email address.
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestAttribute("userId") Long userId) {
        svc.resendVerificationEmail(userId);
        return ResponseEntity.accepted().build();
    }

    // Always returns 202 regardless of whether the email exists — see UserSvc
    // for why (avoids leaking which emails are registered).
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        svc.forgotPassword(req.getEmail());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        svc.resetPassword(req.getToken(), req.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    // Current authenticated user's own profile.
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@RequestAttribute("userId") Long userId) {
        return ResponseEntity.ok(svc.findById(userId));
    }

    // Update the current user's own name/email/password.
    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody UpdateProfileRequest req
    ) {
        return ResponseEntity.ok(svc.updateProfile(userId, req));
    }

    // List all users (admin directory). Requires a valid JWT; admin-only.
    @GetMapping
    public List<UserResponse> getAll(@RequestAttribute("userRole") String role) {
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Only admins may list all users");
        }
        return svc.listAll();
    }

    // Delete user by id. Allowed for the account owner or an ADMIN.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") long id,
            @RequestAttribute("userId") Long requesterId,
            @RequestAttribute("userRole") String requesterRole
    ) {
        boolean deleted = svc.deleteById(id, requesterId, requesterRole);
        if (!deleted)
            return ResponseEntity.notFound().build();
        return ResponseEntity.noContent().build();
    }
}
