package com.catalogix.user.svc;

import com.catalogix.user.client.NotificationClient;
import com.catalogix.user.dto.AuthResponse;
import com.catalogix.user.dto.CreateUserRequest;
import com.catalogix.user.dto.LoginRequest;
import com.catalogix.user.dto.TokenPairResponse;
import com.catalogix.user.dto.UpdateProfileRequest;
import com.catalogix.user.dto.UserResponse;
import com.catalogix.user.exception.ForbiddenException;
import com.catalogix.user.exception.UnauthorizedException;
import com.catalogix.user.model.EmailVerificationToken;
import com.catalogix.user.model.PasswordResetToken;
import com.catalogix.user.model.User;
import com.catalogix.user.repository.EmailVerificationTokenRepository;
import com.catalogix.user.repository.PasswordResetTokenRepository;
import com.catalogix.user.repository.UserRepository;
import com.catalogix.user.security.JwtService;
import com.catalogix.user.security.LoginAttemptTracker;
import com.catalogix.user.security.RefreshTokenService;
import com.catalogix.user.security.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Business logic for users:
 * - register (hash password, issue access + refresh tokens, send a verification email)
 * - login (verify password with brute-force lockout, issue tokens)
 * - refresh (rotate a refresh token for a new access token)
 * - forgotPassword / resetPassword, verifyEmail, updateProfile
 * - listAll / findById (return DTOs)
 * - deleteById (self or ADMIN only)
 */
@Service
public class UserSvc {

    private static final long EMAIL_VERIFICATION_TTL_MS = 24L * 60 * 60 * 1000; // 24h
    private static final long PASSWORD_RESET_TTL_MS = 60L * 60 * 1000;          // 1h

    private final UserRepository repo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptTracker loginAttemptTracker;
    private final EmailVerificationTokenRepository emailVerificationRepo;
    private final PasswordResetTokenRepository passwordResetRepo;
    private final TokenHasher tokenHasher;
    private final NotificationClient notificationClient;
    private final String frontendBaseUrl;
    private final Set<String> adminEmails;

    public UserSvc(
            UserRepository repo,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            LoginAttemptTracker loginAttemptTracker,
            EmailVerificationTokenRepository emailVerificationRepo,
            PasswordResetTokenRepository passwordResetRepo,
            TokenHasher tokenHasher,
            NotificationClient notificationClient,
            @Value("${FRONTEND_BASE_URL:http://localhost:8080}") String frontendBaseUrl,
            @Value("${ADMIN_EMAILS:}") String adminEmailsCsv
    ) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptTracker = loginAttemptTracker;
        this.emailVerificationRepo = emailVerificationRepo;
        this.passwordResetRepo = passwordResetRepo;
        this.tokenHasher = tokenHasher;
        this.notificationClient = notificationClient;
        this.frontendBaseUrl = frontendBaseUrl;
        this.adminEmails = Arrays.stream(adminEmailsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    // Register a new user. Throws IllegalArgumentException if email already exists.
    // @Transactional ensures the findByEmail check and save() are in the same DB transaction,
    // preventing a race where two concurrent requests register the same email.
    @Transactional
    public AuthResponse register(CreateUserRequest req) {
        if (repo.findByEmail(req.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = new User();
        user.setName(req.getName());
        user.setEmail(req.getEmail());
        user.setPassword(passwordEncoder.encode(req.getPassword())); // Hash password before saving
        user.setRole(adminEmails.contains(req.getEmail().toLowerCase()) ? "ADMIN" : "USER");

        User saved = repo.save(user);
        sendVerificationEmail(saved);
        return issueAuthResponse(saved);
    }

    // Login user: returns fresh tokens + profile if credentials are valid.
    // Brute-force guard: after MAX_ATTEMPTS consecutive failures for an email,
    // further attempts are rejected for a cool-down window regardless of whether
    // the password given this time is actually correct.
    @Transactional
    public AuthResponse login(LoginRequest req) {
        loginAttemptTracker.assertNotLocked(req.getEmail());

        User user = repo.findByEmail(req.getEmail()).orElse(null);
        boolean passwordOk = user != null && passwordEncoder.matches(req.getPassword(), user.getPassword());

        if (!passwordOk) {
            loginAttemptTracker.recordFailure(req.getEmail());
            throw new UnauthorizedException("Invalid email or password");
        }

        loginAttemptTracker.recordSuccess(req.getEmail());
        return issueAuthResponse(user);
    }

    // Exchange a valid refresh token for a new access token (and a rotated refresh token).
    @Transactional
    public TokenPairResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(refreshToken);
        User user = repo.findById(rotation.userId())
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists"));

        String accessToken = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new TokenPairResponse(accessToken, jwtService.getExpirationMs(), rotation.newRefreshToken());
    }

    // Revoke a single refresh token (log out this session only).
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    // Revoke every refresh token belonging to a user (log out everywhere).
    public void logoutEverywhere(Long userId) {
        refreshTokenService.revokeAllForUser(userId);
    }

    // Kicks off (or re-sends) the email verification link for a newly-created
    // or not-yet-verified account. Best-effort — NotificationClient itself
    // swallows delivery failures, so this never blocks registration.
    private void sendVerificationEmail(User user) {
        String rawToken = tokenHasher.generateRawToken();
        emailVerificationRepo.save(new EmailVerificationToken(
                user.getId(), tokenHasher.hash(rawToken), Instant.now().plusMillis(EMAIL_VERIFICATION_TTL_MS)));

        String link = frontendBaseUrl + "/verify-email?token=" + rawToken;
        notificationClient.sendEmail(
                user.getEmail(),
                "Verify your Catalogix email",
                "Hi " + user.getName() + ",\n\n"
                        + "Please verify your email address by visiting the link below:\n" + link + "\n\n"
                        + "This link expires in 24 hours. If you didn't create this account, you can ignore this email.");
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        EmailVerificationToken token = emailVerificationRepo.findByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired verification link"));
        if (!token.isValid(Instant.now())) {
            throw new UnauthorizedException("Invalid or expired verification link");
        }

        User user = repo.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists"));
        user.setVerified(true);
        repo.save(user);

        token.setUsed(true);
        emailVerificationRepo.save(token);
    }

    // For when the original verification email was lost, expired, or never
    // arrived. A no-op (not an error) if the account is already verified.
    @Transactional
    public void resendVerificationEmail(Long userId) {
        User user = repo.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists"));
        if (!user.isVerified()) {
            sendVerificationEmail(user);
        }
    }

    // Deliberately never reveals whether the email exists — this always
    // "succeeds" from the caller's point of view, to avoid account enumeration.
    @Transactional
    public void forgotPassword(String email) {
        repo.findByEmail(email).ifPresent(user -> {
            String rawToken = tokenHasher.generateRawToken();
            passwordResetRepo.save(new PasswordResetToken(
                    user.getId(), tokenHasher.hash(rawToken), Instant.now().plusMillis(PASSWORD_RESET_TTL_MS)));

            String link = frontendBaseUrl + "/reset-password?token=" + rawToken;
            notificationClient.sendEmail(
                    user.getEmail(),
                    "Reset your Catalogix password",
                    "Hi " + user.getName() + ",\n\n"
                            + "Reset your password by visiting the link below:\n" + link + "\n\n"
                            + "This link expires in 1 hour. If you didn't request this, you can safely ignore this email.");
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = passwordResetRepo.findByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired reset link"));
        if (!token.isValid(Instant.now())) {
            throw new UnauthorizedException("Invalid or expired reset link");
        }

        User user = repo.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists"));
        user.setPassword(passwordEncoder.encode(newPassword));
        repo.save(user);

        token.setUsed(true);
        passwordResetRepo.save(token);

        // A stolen refresh token from before the reset shouldn't keep working.
        refreshTokenService.revokeAllForUser(user.getId());
    }

    // Updates name/email/password for the current user. Changing email or
    // password requires currentPassword as a lightweight re-auth check.
    // Changing email resets verified to false and re-sends a verification email.
    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = repo.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists"));

        boolean changingEmail = StringUtils.hasText(req.getEmail()) && !req.getEmail().equalsIgnoreCase(user.getEmail());
        boolean changingPassword = StringUtils.hasText(req.getNewPassword());

        if (changingEmail || changingPassword) {
            boolean currentPasswordOk = StringUtils.hasText(req.getCurrentPassword())
                    && passwordEncoder.matches(req.getCurrentPassword(), user.getPassword());
            if (!currentPasswordOk) {
                throw new UnauthorizedException(
                        "currentPassword is required and must be correct to change email or password");
            }
        }

        if (StringUtils.hasText(req.getName())) {
            user.setName(req.getName());
        }
        if (changingEmail) {
            if (repo.findByEmail(req.getEmail()).isPresent()) {
                throw new IllegalArgumentException("Email already registered");
            }
            user.setEmail(req.getEmail());
            user.setVerified(false);
        }
        if (changingPassword) {
            user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        }

        User saved = repo.save(user);
        if (changingEmail) {
            sendVerificationEmail(saved);
        }
        return toResponse(saved);
    }

    // List all users as DTOs (no passwords). Admin-only — enforced by the controller.
    @Transactional(readOnly = true)
    public List<UserResponse> listAll() {
        return repo.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return repo.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
    }

    // Delete user by id. Only the user themselves or an ADMIN may do this.
    // @Transactional ensures the check and delete are in the same DB transaction, preventing a race
    // where the user is deleted between the existsById check and deleteById call.
    @Transactional
    public boolean deleteById(Long id, Long requesterId, String requesterRole) {
        if (id == null) {
            return false;
        }
        boolean isSelf = id.equals(requesterId);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(requesterRole);
        if (!isSelf && !isAdmin) {
            throw new ForbiddenException("You may only delete your own account");
        }
        if (!repo.existsById(id)) {
            return false;
        }
        refreshTokenService.revokeAllForUser(id);
        repo.deleteById(id);
        return true;
    }

    private AuthResponse issueAuthResponse(User user) {
        String accessToken = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = refreshTokenService.issue(user.getId());
        return new AuthResponse(accessToken, jwtService.getExpirationMs(), refreshToken, toResponse(user));
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getName(), u.getEmail(), u.getRole(), u.isVerified());
    }
}
