package com.catalogix.user.svc;

import com.catalogix.user.client.NotificationClient;
import com.catalogix.user.dto.AuthResponse;
import com.catalogix.user.dto.CreateUserRequest;
import com.catalogix.user.dto.LoginRequest;
import com.catalogix.user.dto.TokenPairResponse;
import com.catalogix.user.dto.UpdateProfileRequest;
import com.catalogix.user.dto.UserResponse;
import com.catalogix.user.exception.AccountLockedException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UserSvcTest {

    @Mock private UserRepository repo;
    @Mock private PasswordEncoder encoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private LoginAttemptTracker loginAttemptTracker;
    @Mock private EmailVerificationTokenRepository emailVerificationRepo;
    @Mock private PasswordResetTokenRepository passwordResetRepo;
    @Mock private TokenHasher tokenHasher;
    @Mock private NotificationClient notificationClient;

    // Constructed by hand (rather than @InjectMocks) since the constructor also
    // takes plain strings (FRONTEND_BASE_URL, ADMIN_EMAILS), which Mockito can't auto-mock.
    private UserSvc svc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new UserSvc(repo, encoder, jwtService, refreshTokenService, loginAttemptTracker,
                emailVerificationRepo, passwordResetRepo, tokenHasher, notificationClient,
                "http://localhost:8080", "admin@example.com");
        lenient().when(jwtService.generateToken(any(), any(), any())).thenReturn("fake.access.token");
        lenient().when(jwtService.getExpirationMs()).thenReturn(900_000L);
        lenient().when(refreshTokenService.issue(any())).thenReturn("fake-refresh-token");
        lenient().when(tokenHasher.generateRawToken()).thenReturn("raw-token");
        lenient().when(tokenHasher.hash(anyString())).thenAnswer(inv -> "hash(" + inv.getArgument(0) + ")");
    }

    private User sampleUser(Long id, String email, String hashedPassword) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setName("Name");
        u.setPassword(hashedPassword);
        u.setRole("USER");
        return u;
    }

    // Register tests
    @Test
    @SuppressWarnings("null")
    void registerSucceedsAssignsUserRoleAndSendsVerificationEmail() {
        CreateUserRequest req = new CreateUserRequest();
        req.setName("Name");
        req.setEmail("name@example.com");
        req.setPassword("Password1");

        when(repo.findByEmail(req.getEmail())).thenReturn(Optional.empty());
        when(encoder.encode(req.getPassword())).thenReturn("hashed");
        when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });

        AuthResponse resp = svc.register(req);
        assertNotNull(resp);
        assertEquals("fake.access.token", resp.getAccessToken());
        assertEquals(1L, resp.getUser().getId());
        assertEquals("USER", resp.getUser().getRole());
        assertFalse(resp.getUser().isVerified());
        verify(emailVerificationRepo).save(any(EmailVerificationToken.class));
        verify(notificationClient).sendEmail(eq("name@example.com"), contains("Verify"), anyString());
    }

    @Test
    @SuppressWarnings("null")
    void registerAssignsAdminRoleForAllowlistedEmail() {
        CreateUserRequest req = new CreateUserRequest();
        req.setName("Boss");
        req.setEmail("Admin@Example.com");
        req.setPassword("Password1");

        when(repo.findByEmail(req.getEmail())).thenReturn(Optional.empty());
        when(encoder.encode(req.getPassword())).thenReturn("hashed");
        when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });

        assertEquals("ADMIN", svc.register(req).getUser().getRole());
    }

    @Test
    void registerDuplicateEmailThrows() {
        CreateUserRequest req = new CreateUserRequest();
        req.setName("A"); req.setEmail("a@a.com"); req.setPassword("Password1");
        when(repo.findByEmail(req.getEmail())).thenReturn(Optional.of(new User()));

        assertThrows(IllegalArgumentException.class, () -> svc.register(req));
        verify(repo, never()).save(any());
    }

    // Login tests
    @Test
    void loginSucceeds() {
        User u = sampleUser(1L, "x@x.com", "hashed");
        when(repo.findByEmail("x@x.com")).thenReturn(Optional.of(u));
        when(encoder.matches("Password1", "hashed")).thenReturn(true);

        LoginRequest req = new LoginRequest();
        req.setEmail("x@x.com"); req.setPassword("Password1");

        AuthResponse resp = svc.login(req);
        assertEquals(1L, resp.getUser().getId());
        verify(loginAttemptTracker).recordSuccess("x@x.com");
    }

    @Test
    void loginFailsWithBadPasswordRecordsFailure() {
        User u = sampleUser(1L, "x@x.com", "hashed");
        when(repo.findByEmail("x@x.com")).thenReturn(Optional.of(u));
        when(encoder.matches("wrongpass", "hashed")).thenReturn(false);

        LoginRequest req = new LoginRequest();
        req.setEmail("x@x.com"); req.setPassword("wrongpass");

        assertThrows(UnauthorizedException.class, () -> svc.login(req));
        verify(loginAttemptTracker).recordFailure("x@x.com");
    }

    @Test
    void loginRejectsWhenAccountLocked() {
        doThrow(new AccountLockedException(java.time.Duration.ofMinutes(5)))
                .when(loginAttemptTracker).assertNotLocked("locked@x.com");

        LoginRequest req = new LoginRequest();
        req.setEmail("locked@x.com"); req.setPassword("whatever");

        assertThrows(AccountLockedException.class, () -> svc.login(req));
        verify(repo, never()).findByEmail(anyString());
    }

    // refresh / logout tests
    @Test
    void refreshRotatesTokenAndReturnsNewPair() {
        User u = sampleUser(7L, "x@x.com", "hashed");
        when(refreshTokenService.rotate("old-token"))
                .thenReturn(new RefreshTokenService.RotationResult(7L, "new-refresh-token"));
        when(repo.findById(7L)).thenReturn(Optional.of(u));

        TokenPairResponse resp = svc.refresh("old-token");
        assertEquals("new-refresh-token", resp.getRefreshToken());
    }

    @Test
    void logoutRevokesGivenToken() {
        svc.logout("some-token");
        verify(refreshTokenService).revoke("some-token");
    }

    @Test
    void logoutEverywhereRevokesAllForUser() {
        svc.logoutEverywhere(5L);
        verify(refreshTokenService).revokeAllForUser(5L);
    }

    // email verification tests
    @Test
    void verifyEmailMarksUserVerified() {
        EmailVerificationToken token = new EmailVerificationToken(1L, "hash(raw-token)", Instant.now().plusSeconds(60));
        when(emailVerificationRepo.findByTokenHash("hash(raw-token)")).thenReturn(Optional.of(token));
        User u = sampleUser(1L, "x@x.com", "hashed");
        when(repo.findById(1L)).thenReturn(Optional.of(u));

        svc.verifyEmail("raw-token");

        assertTrue(u.isVerified());
        assertTrue(token.isUsed());
        verify(repo).save(u);
    }

    @Test
    void verifyEmailRejectsExpiredToken() {
        EmailVerificationToken token = new EmailVerificationToken(1L, "hash(raw-token)", Instant.now().minusSeconds(60));
        when(emailVerificationRepo.findByTokenHash("hash(raw-token)")).thenReturn(Optional.of(token));

        assertThrows(UnauthorizedException.class, () -> svc.verifyEmail("raw-token"));
        verify(repo, never()).save(any());
    }

    @Test
    void verifyEmailRejectsUnknownToken() {
        when(emailVerificationRepo.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThrows(UnauthorizedException.class, () -> svc.verifyEmail("bogus"));
    }

    @Test
    @SuppressWarnings("null")
    void resendVerificationEmailSendsAgainWhenUnverified() {
        User u = sampleUser(1L, "x@x.com", "hashed");
        u.setVerified(false);
        when(repo.findById(1L)).thenReturn(Optional.of(u));

        svc.resendVerificationEmail(1L);

        verify(notificationClient).sendEmail(eq("x@x.com"), contains("Verify"), anyString());
    }

    @Test
    void resendVerificationEmailIsNoOpWhenAlreadyVerified() {
        User u = sampleUser(1L, "x@x.com", "hashed");
        u.setVerified(true);
        when(repo.findById(1L)).thenReturn(Optional.of(u));

        svc.resendVerificationEmail(1L);

        verify(notificationClient, never()).sendEmail(anyString(), anyString(), anyString());
    }

    // forgot/reset password tests
    @Test
    void forgotPasswordSendsEmailWhenAccountExists() {
        User u = sampleUser(1L, "x@x.com", "hashed");
        when(repo.findByEmail("x@x.com")).thenReturn(Optional.of(u));

        svc.forgotPassword("x@x.com");

        verify(passwordResetRepo).save(any(PasswordResetToken.class));
        verify(notificationClient).sendEmail(eq("x@x.com"), contains("Reset"), anyString());
    }

    @Test
    void forgotPasswordSilentlyNoOpsWhenAccountDoesNotExist() {
        when(repo.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        svc.forgotPassword("nobody@example.com"); // should not throw

        verify(passwordResetRepo, never()).save(any());
        verify(notificationClient, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void resetPasswordUpdatesPasswordAndRevokesSessions() {
        PasswordResetToken token = new PasswordResetToken(1L, "hash(raw-token)", Instant.now().plusSeconds(60));
        when(passwordResetRepo.findByTokenHash("hash(raw-token)")).thenReturn(Optional.of(token));
        User u = sampleUser(1L, "x@x.com", "oldHashed");
        when(repo.findById(1L)).thenReturn(Optional.of(u));
        when(encoder.encode("NewPassword1")).thenReturn("newHashed");

        svc.resetPassword("raw-token", "NewPassword1");

        assertEquals("newHashed", u.getPassword());
        assertTrue(token.isUsed());
        verify(refreshTokenService).revokeAllForUser(1L);
    }

    @Test
    void resetPasswordRejectsExpiredToken() {
        PasswordResetToken token = new PasswordResetToken(1L, "hash(raw-token)", Instant.now().minusSeconds(60));
        when(passwordResetRepo.findByTokenHash("hash(raw-token)")).thenReturn(Optional.of(token));

        assertThrows(UnauthorizedException.class, () -> svc.resetPassword("raw-token", "NewPassword1"));
    }

    // updateProfile tests
    @Test
    void updateProfileChangesNameWithoutRequiringPassword() {
        User u = sampleUser(1L, "x@x.com", "hashed");
        when(repo.findById(1L)).thenReturn(Optional.of(u));
        when(repo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setName("New Name");

        UserResponse resp = svc.updateProfile(1L, req);
        assertEquals("New Name", resp.getName());
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void updateProfileRequiresCurrentPasswordToChangeEmail() {
        User u = sampleUser(1L, "old@x.com", "hashed");
        when(repo.findById(1L)).thenReturn(Optional.of(u));

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setEmail("new@x.com");
        // no currentPassword supplied

        assertThrows(UnauthorizedException.class, () -> svc.updateProfile(1L, req));
        verify(repo, never()).save(any());
    }

    @Test
    @SuppressWarnings("null")
    void updateProfileChangesEmailAndResendsVerification() {
        User u = sampleUser(1L, "old@x.com", "hashed");
        u.setVerified(true);
        when(repo.findById(1L)).thenReturn(Optional.of(u));
        when(repo.findByEmail("new@x.com")).thenReturn(Optional.empty());
        when(encoder.matches("CorrectPass1", "hashed")).thenReturn(true);
        when(repo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setEmail("new@x.com");
        req.setCurrentPassword("CorrectPass1");

        UserResponse resp = svc.updateProfile(1L, req);

        assertEquals("new@x.com", resp.getEmail());
        assertFalse(resp.isVerified()); // reset since the email changed
        verify(notificationClient).sendEmail(eq("new@x.com"), contains("Verify"), anyString());
    }

    @Test
    void updateProfileRejectsEmailAlreadyTaken() {
        User u = sampleUser(1L, "old@x.com", "hashed");
        when(repo.findById(1L)).thenReturn(Optional.of(u));
        when(encoder.matches("CorrectPass1", "hashed")).thenReturn(true);
        when(repo.findByEmail("taken@x.com")).thenReturn(Optional.of(sampleUser(2L, "taken@x.com", "x")));

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setEmail("taken@x.com");
        req.setCurrentPassword("CorrectPass1");

        assertThrows(IllegalArgumentException.class, () -> svc.updateProfile(1L, req));
    }

    // listAll / deleteById tests (unchanged behavior, still covered)
    @Test
    void listAllReturnsMappedDTOs() {
        User u1 = sampleUser(1L, "alice@x.com", "h"); u1.setName("Alice"); u1.setRole("ADMIN");
        User u2 = sampleUser(2L, "bob@x.com", "h");   u2.setName("Bob");
        when(repo.findAll()).thenReturn(List.of(u1, u2));

        List<UserResponse> result = svc.listAll();
        assertEquals(2, result.size());
        assertEquals("Alice", result.get(0).getName());
    }

    @Test
    void deleteByIdAllowsSelfAndRevokesTokens() {
        when(repo.existsById(1L)).thenReturn(true);
        assertTrue(svc.deleteById(1L, 1L, "USER"));
        verify(repo).deleteById(1L);
        verify(refreshTokenService).revokeAllForUser(1L);
    }

    @Test
    void deleteByIdRejectsOtherNonAdminUsers() {
        assertThrows(ForbiddenException.class, () -> svc.deleteById(1L, 2L, "USER"));
        verify(repo, never()).deleteById(anyLong());
    }
}
