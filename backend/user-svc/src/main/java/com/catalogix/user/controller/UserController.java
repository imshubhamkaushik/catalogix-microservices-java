package com.catalogix.user.controller;

import com.catalogix.user.dto.AuthResponse;
import com.catalogix.user.dto.CreateUserRequest;
import com.catalogix.user.dto.ForgotPasswordRequest;
import com.catalogix.user.dto.LoginRequest;
import com.catalogix.user.dto.NotificationPreferencesRequest;
import com.catalogix.user.dto.NotificationPreferencesResponse;
import com.catalogix.user.dto.ResetPasswordRequest;
import com.catalogix.user.dto.RoleAssignmentRequest;
import com.catalogix.user.dto.SessionResponse;
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
    // User-Agent is captured purely for the session-list UI (see
    // GET /users/me/sessions) — never validated or required.
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody CreateUserRequest req,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            HttpServletResponse response
    ) {
        AuthResponse created = svc.register(req, userAgent);
        setRefreshCookie(response, created.getRefreshToken());
        return ResponseEntity.status(201).body(created);
    }

    // Login endpoint: validates credentials and returns tokens + profile on success.
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest req,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            HttpServletResponse response
    ) {
        AuthResponse result = svc.login(req, userAgent);
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

    // Update the current user's own name/email/password. A password change signs out
    // every OTHER session; this request's own refresh cookie identifies the one to keep.
    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody UpdateProfileRequest req,
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken
    ) {
        return ResponseEntity.ok(svc.updateProfile(userId, req, refreshToken));
    }

    // List all users (admin directory). Requires a valid JWT; admin-only.
    @GetMapping
    public List<UserResponse> getAll(@RequestAttribute("userRole") String role) {
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Only admins may list all users");
        }
        return svc.listAll();
    }

    // Every active session (unexpired, unrevoked refresh token) for the
    // current user, newest-activity first. Flags which one is this device
    // by comparing against the refresh-token cookie on THIS request — see
    // UserSvc.listSessions and SessionResponse's Javadoc.
    @GetMapping("/me/sessions")
    public List<SessionResponse> listSessions(
            @RequestAttribute("userId") Long userId,
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken
    ) {
        return svc.listSessions(userId, refreshToken);
    }

    // Revokes one specific session (not "this device", not "everywhere" —
    // see /logout and /logout-all for those). A no-op, not an error, if the
    // id doesn't exist or already isn't active — same "logout should never
    // fail loudly" reasoning as /logout.
    @DeleteMapping("/me/sessions/{id}")
    public ResponseEntity<Void> revokeSession(
            @PathVariable("id") Long sessionId,
            @RequestAttribute("userId") Long userId
    ) {
        svc.revokeSession(sessionId, userId);
        return ResponseEntity.noContent().build();
    }

    // Updates both notification preference toggles for the current user.
    // See UserSvc.updateNotificationPreferences's Javadoc for the honest
    // caveat on what this does and doesn't do yet.
    @PatchMapping("/me/notification-preferences")
    public ResponseEntity<UserResponse> updateNotificationPreferences(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody NotificationPreferencesRequest req
    ) {
        return ResponseEntity.ok(svc.updateNotificationPreferences(userId, req));
    }

    // A customer asks to become a seller. This only records a PENDING request —
    // an admin approves it (PUT /users/{id}/role) or rejects it
    // (DELETE /users/{id}/role-request). See UserSvc.becomeSeller.
    @PostMapping("/me/become-seller")
    public ResponseEntity<UserResponse> becomeSeller(@RequestAttribute("userId") Long userId) {
        return ResponseEntity.ok(svc.becomeSeller(userId));
    }

    // Admin-only: assign a role (USER / SELLER / ADMIN) to a user. This is how a
    // pending seller request is approved, and the only way anyone becomes an admin.
    @PutMapping("/{id}/role")
    public ResponseEntity<UserResponse> assignRole(
            @PathVariable Long id,
            @Valid @RequestBody RoleAssignmentRequest req,
            @RequestAttribute("userId") Long adminId,
            @RequestAttribute("userRole") String role
    ) {
        requireAdmin(role, "Only admins may assign roles");
        return ResponseEntity.ok(svc.assignRole(id, req.getRole(), adminId));
    }

    // Admin-only: decline a pending role request (the user keeps their current role).
    @DeleteMapping("/{id}/role-request")
    public ResponseEntity<UserResponse> rejectRoleRequest(
            @PathVariable Long id,
            @RequestAttribute("userRole") String role
    ) {
        requireAdmin(role, "Only admins may review role requests");
        return ResponseEntity.ok(svc.rejectRoleRequest(id));
    }

    private static void requireAdmin(String role, String message) {
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new ForbiddenException(message);
        }
    }

    // Internal-only: notification-svc calls this (with a SYSTEM-minted
    // token, same mechanism as checkout-svc -> inventory-svc's /adjust) to
    // decide whether an order-status email should actually be sent. Not for
    // any regular user token — a user has no business learning another
    // user's preferences, and their own are already available via GET /me.
    @GetMapping("/{id}/notification-preferences")
    public NotificationPreferencesResponse getNotificationPreferences(
            @PathVariable("id") Long id,
            @RequestAttribute("userRole") String role
    ) {
        if (!"SYSTEM".equalsIgnoreCase(role)) {
            throw new ForbiddenException("This lookup is for internal service calls only");
        }
        return svc.getNotificationPreferences(id);
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
