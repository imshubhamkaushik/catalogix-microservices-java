package com.catalogix.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.catalogix.user.dto.AuthResponse;
import com.catalogix.user.dto.CreateUserRequest;
import com.catalogix.user.dto.ForgotPasswordRequest;
import com.catalogix.user.dto.LoginRequest;
import com.catalogix.user.dto.RefreshRequest;
import com.catalogix.user.dto.ResetPasswordRequest;
import com.catalogix.user.dto.TokenPairResponse;
import com.catalogix.user.dto.UpdateProfileRequest;
import com.catalogix.user.dto.UserResponse;
import com.catalogix.user.exception.AccountLockedException;
import com.catalogix.user.exception.ForbiddenException;
import com.catalogix.user.exception.UnauthorizedException;
import com.catalogix.user.security.JwtAuthFilter;
import com.catalogix.user.security.RateLimiterFilter;
import com.catalogix.user.svc.UserSvc;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
@WebMvcTest(
        controllers = UserController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private UserSvc svc;

    @Autowired
    private MockMvc mvc;

    private AuthResponse sampleAuthResponse() {
        UserResponse profile = new UserResponse(1L, "John", "john@example.com", "USER");
        return new AuthResponse("fake.access.token", 900000L, "fake-refresh-token", profile);
    }

    // POST /users/register tests
    @Test
    @SuppressWarnings("null")
    void registerReturnsCreated() throws Exception {
        CreateUserRequest req = new CreateUserRequest();
        req.setName("John");
        req.setEmail("john@example.com");
        req.setPassword("Password1");

        when(svc.register(any())).thenReturn(sampleAuthResponse());

        mvc.perform(post("/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("fake.access.token"))
                .andExpect(jsonPath("$.refreshToken").value("fake-refresh-token"))
                .andExpect(jsonPath("$.user.id").value(1L))
                .andExpect(jsonPath("$.user.name").value("John"))
                .andExpect(jsonPath("$.user.email").value("john@example.com"));
    }

    @Test
    @SuppressWarnings("null")
    void registerDuplicateEmailReturns409() throws Exception {
        CreateUserRequest req = new CreateUserRequest();
        req.setName("John");
        req.setEmail("john@example.com");
        req.setPassword("Password1");

        when(svc.register(any())).thenThrow(new IllegalArgumentException("Email already registered"));

        mvc.perform(post("/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    // POST /users/login tests
    @Test
    @SuppressWarnings("null")
    void loginReturnsOkWithTokens() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("john@example.com");
        req.setPassword("Password1");

        when(svc.login(any())).thenReturn(sampleAuthResponse());

        mvc.perform(post("/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("fake.access.token"))
                .andExpect(jsonPath("$.refreshToken").value("fake-refresh-token"))
                .andExpect(jsonPath("$.user.email").value("john@example.com"));
    }

    @Test
    @SuppressWarnings("null")
    void loginFailureReturns401() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("x@x.com");
        req.setPassword("wrongpass");

        when(svc.login(any())).thenThrow(new UnauthorizedException("Invalid email or password"));

        mvc.perform(post("/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @SuppressWarnings("null")
    void loginLockedReturns429WithRetryAfterHeader() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("locked@x.com");
        req.setPassword("whatever");

        when(svc.login(any())).thenThrow(new AccountLockedException(Duration.ofSeconds(120)));

        mvc.perform(post("/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "120"));
    }

    // POST /users/refresh tests
    @Test
    @SuppressWarnings("null")
    void refreshReturnsNewTokenPair() throws Exception {
        RefreshRequest req = new RefreshRequest();
        req.setRefreshToken("old-token");

        when(svc.refresh("old-token"))
                .thenReturn(new TokenPairResponse("new.access.token", 900000L, "new-refresh-token"));

        mvc.perform(post("/users/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.token"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"));
    }

    @Test
    @SuppressWarnings("null")
    void refreshWithInvalidTokenReturns401() throws Exception {
        RefreshRequest req = new RefreshRequest();
        req.setRefreshToken("bogus");

        when(svc.refresh("bogus")).thenThrow(new UnauthorizedException("Invalid refresh token"));

        mvc.perform(post("/users/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // POST /users/logout, /users/logout-all
    @Test
    @SuppressWarnings("null")
    void logoutReturnsNoContent() throws Exception {
        RefreshRequest req = new RefreshRequest();
        req.setRefreshToken("some-token");

        mvc.perform(post("/users/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(svc).logout("some-token");
    }

    @Test
    void logoutAllReturnsNoContent() throws Exception {
        mvc.perform(post("/users/logout-all").requestAttr("userId", 1L))
                .andExpect(status().isNoContent());

        verify(svc).logoutEverywhere(1L);
    }

    // GET /users tests (admin-only directory)

    @Test
    @SuppressWarnings("null")
    void getAllReturnsListOfUsersForAdmin() throws Exception {
        when(svc.listAll()).thenReturn(List.of(
                new UserResponse(1L, "Alice", "alice@example.com", "ADMIN"),
                new UserResponse(2L, "Bob",   "bob@example.com", "USER")
        ));

        mvc.perform(get("/users")
                .requestAttr("userId", 1L)
                .requestAttr("userRole", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Alice"))
                .andExpect(jsonPath("$[1].name").value("Bob"));
    }

    @Test
    void getAllRejectsNonAdmin() throws Exception {
        mvc.perform(get("/users")
                .requestAttr("userId", 2L)
                .requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    // DELETE /users/{id} tests

    @Test
    void deleteReturnsNoContentForSelf() throws Exception {
        when(svc.deleteById(1L, 1L, "USER")).thenReturn(true);

        mvc.perform(delete("/users/1")
                .requestAttr("userId", 1L)
                .requestAttr("userRole", "USER"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturnsNotFoundWhenUserMissing() throws Exception {
        when(svc.deleteById(99L, 1L, "ADMIN")).thenReturn(false);

        mvc.perform(delete("/users/99")
                .requestAttr("userId", 1L)
                .requestAttr("userRole", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsForbiddenForOtherUsersAccount() throws Exception {
        when(svc.deleteById(anyLong(), anyLong(), anyString()))
                .thenThrow(new ForbiddenException("You may only delete your own account"));

        mvc.perform(delete("/users/2")
                .requestAttr("userId", 1L)
                .requestAttr("userRole", "USER"))
                .andExpect(status().isForbidden());
    }

    // GET /users/verify-email
    @Test
    void verifyEmailReturnsNoContentOnSuccess() throws Exception {
        mvc.perform(get("/users/verify-email").param("token", "raw-token"))
                .andExpect(status().isNoContent());
        verify(svc).verifyEmail("raw-token");
    }

    @Test
    void verifyEmailReturnsUnauthorizedForBadToken() throws Exception {
        doThrow(new UnauthorizedException("Invalid or expired verification link"))
                .when(svc).verifyEmail("bogus");

        mvc.perform(get("/users/verify-email").param("token", "bogus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resendVerificationReturnsAccepted() throws Exception {
        mvc.perform(post("/users/resend-verification").requestAttr("userId", 1L))
                .andExpect(status().isAccepted());
        verify(svc).resendVerificationEmail(1L);
    }

    // POST /users/forgot-password
    @Test
    @SuppressWarnings("null")
    void forgotPasswordAlwaysReturnsAcceptedRegardlessOfWhetherEmailExists() throws Exception {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("maybe@example.com");

        mvc.perform(post("/users/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isAccepted());

        verify(svc).forgotPassword("maybe@example.com");
    }

    // POST /users/reset-password
    @Test
    @SuppressWarnings("null")
    void resetPasswordReturnsNoContentOnSuccess() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("raw-token");
        req.setNewPassword("NewPassword1");

        mvc.perform(post("/users/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(svc).resetPassword("raw-token", "NewPassword1");
    }

    @Test
    @SuppressWarnings("null")
    void resetPasswordReturnsUnauthorizedForExpiredToken() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("expired-token");
        req.setNewPassword("NewPassword1");

        doThrow(new UnauthorizedException("Invalid or expired reset link"))
                .when(svc).resetPassword("expired-token", "NewPassword1");

        mvc.perform(post("/users/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // PATCH /users/me
    @Test
    @SuppressWarnings("null")
    void updateProfileReturnsUpdatedUser() throws Exception {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setName("New Name");

        when(svc.updateProfile(eq(1L), any(UpdateProfileRequest.class)))
                .thenReturn(new UserResponse(1L, "New Name", "x@x.com", "USER", true));

        mvc.perform(patch("/users/me")
                .requestAttr("userId", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    @SuppressWarnings("null")
    void updateProfileReturnsUnauthorizedWhenCurrentPasswordMissing() throws Exception {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setEmail("new@x.com");

        when(svc.updateProfile(eq(1L), any(UpdateProfileRequest.class)))
                .thenThrow(new UnauthorizedException("currentPassword is required and must be correct"));

        mvc.perform(patch("/users/me")
                .requestAttr("userId", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
}
