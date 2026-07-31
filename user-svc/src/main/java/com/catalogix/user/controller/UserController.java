package com.catalogix.user.controller;

import com.catalogix.user.dto.AuthResponse;
import com.catalogix.user.dto.CreateUserRequest;
import com.catalogix.user.dto.ForgotPasswordRequest;
import com.catalogix.user.dto.LoginRequest;
import com.catalogix.user.dto.RefreshRequest;
import com.catalogix.user.dto.ResetPasswordRequest;
import com.catalogix.user.dto.TokenPairResponse;
import com.catalogix.user.dto.UpdateProfileRequest;
import com.catalogix.user.dto.UserResponse;
import com.catalogix.user.exception.ForbiddenException;
import com.catalogix.user.svc.UserSvc;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserSvc svc;

    public UserController(UserSvc svc) {
        this.svc = svc;
    }

    // Register endpoint: creates a new user, sends a verification email, and
    // immediately returns tokens so the frontend can log the user straight in.
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody CreateUserRequest req) {
        AuthResponse created = svc.register(req);
        return ResponseEntity.status(201).body(created);
    }

    // Login endpoint: validates credentials and returns tokens + profile on success.
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(svc.login(req));
    }

    // Exchanges a still-valid refresh token for a new access token (and a
    // rotated refresh token — the old one stops working the moment this succeeds).
    @PostMapping("/refresh")
    public ResponseEntity<TokenPairResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ResponseEntity.ok(svc.refresh(req.getRefreshToken()));
    }

    // Logs out this session only, by revoking the given refresh token. The
    // still-live access token remains valid until it naturally expires
    // (it's short-lived by design — see JWT_EXPIRATION_MS).
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        svc.logout(req.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    // Logs out every session for the current account (revokes all refresh tokens).
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutEverywhere(@RequestAttribute("userId") Long userId) {
        svc.logoutEverywhere(userId);
        return ResponseEntity.noContent().build();
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
