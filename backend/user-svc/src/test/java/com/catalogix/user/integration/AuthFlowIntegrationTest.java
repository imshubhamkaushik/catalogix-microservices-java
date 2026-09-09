package com.catalogix.user.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a bug found while reviewing this repo: JwtAuthFilter
 * (security-common) never exempted user-svc's own pre-auth endpoints, so a
 * brand-new user — who by definition has no JWT yet — could never reach
 * /users/register or /users/login. Every existing test in this repo is a
 * slice test: UserControllerTest's @WebMvcTest explicitly excludes
 * JwtAuthFilter from its filter chain, and everything else in this module
 * either mocks the HTTP layer entirely or constructs classes directly with
 * `new`, bypassing Spring/servlet filters altogether. None of that can
 * catch a bug that lives in filter wiring — only a real end-to-end request
 * through the real chain can. This is the first test in the whole repo
 * that does that.
 *
 * NOT compiled or run anywhere in this sandbox — Maven Central isn't on the
 * network allowlist here, so `mvn test` can't resolve dependencies, and
 * there's no Docker daemon available for Testcontainers to start a real
 * Postgres container. Written and reviewed by hand against the real
 * request/response contracts in UserController, CreateUserRequest,
 * LoginRequest, and AuthResponse, but treat it the same way this project's
 * own authors flag their own unverified assumptions elsewhere (e.g.
 * Jenkinsfile.lint's header comment): confirm it actually compiles and
 * passes in a real environment with Docker + Maven Central access before
 * trusting it as a CI gate.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // application-test.properties (the default "test" profile) excludes
        // DataSourceAutoConfiguration/HibernateJpaAutoConfiguration/
        // FlywayAutoConfiguration entirely, because every OTHER test in
        // this service is a slice test that doesn't want a real DB. This
        // class is the exception — it needs the real thing, migrated by
        // the real Flyway scripts in src/main/resources/db/migration, the
        // same way production does. Re-enabling here rather than editing
        // that shared file, so the 866+ lines of existing slice tests stay
        // exactly as fast and DB-free as they were.
        registry.add("spring.autoconfigure.exclude", () -> "");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "never");

        // security.public-paths isn't set in application-test.properties at
        // all (it's user-svc's own application.properties that sets it,
        // and Spring profile precedence means "test" wouldn't automatically
        // inherit it here) — set it explicitly so this test exercises the
        // real production configuration rather than accidentally testing
        // against JwtAuthFilter's permissive-if-unset-everywhere default.
        registry.add("security.public-paths", () ->
                "/users/register,/users/login,/users/refresh,/users/logout,"
                        + "/users/verify-email,/users/forgot-password,/users/reset-password");

        // No RabbitMQ container: UserEventPublisher wraps its
        // rabbitTemplate.convertAndSend() call in try/catch and only logs
        // on failure (see that class), so register()/login() succeeding
        // doesn't depend on a broker being reachable. Confirmed by reading
        // that class directly, not assumed.
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void aBrandNewUserCanRegisterAndThenLogIn_withNoAuthorizationHeaderOnEitherCall() {
        // TestRestTemplate sends no Authorization header unless told to —
        // exactly what a real browser does for a user who has never logged
        // in, and exactly what frontend-svc/src/api.jsx does deliberately
        // (getStoredAccessToken() returns null pre-login). This is the
        // precise scenario that used to 401 before ever reaching
        // UserController.

        String email = "fresh-" + System.nanoTime() + "@example.com";

        Map<String, String> registerBody = Map.of(
                "name", "Fresh User",
                "email", email,
                "password", "Password1"
        );
        ResponseEntity<Map> registerResponse =
                rest.postForEntity(url("/users/register"), registerBody, Map.class);

        assertThat(registerResponse.getStatusCode().value())
                .as("registration must succeed with no Authorization header — "
                        + "this is the exact call that used to 401")
                .isEqualTo(201);
        assertThat(registerResponse.getBody()).containsKey("accessToken");

        Map<String, String> loginBody = Map.of("email", email, "password", "Password1");
        ResponseEntity<Map> loginResponse =
                rest.postForEntity(url("/users/login"), loginBody, Map.class);

        assertThat(loginResponse.getStatusCode().value())
                .as("login must succeed with no Authorization header — "
                        + "this is the exact call that used to 401")
                .isEqualTo(200);
        assertThat(loginResponse.getBody()).containsKey("accessToken");
    }

    @Test
    void theFixDoesNotFailOpen_protectedEndpointsStillRejectRequestsWithNoToken() {
        // The other half of this regression test: shouldNotFilter's new
        // publicPaths check must exempt only the exact configured paths,
        // not accidentally the whole service. /users/me is deliberately
        // not in security.public-paths.
        ResponseEntity<Map> response = rest.getForEntity(url("/users/me"), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void loginWithWrongPasswordStillFails_thePublicPathDoesNotBypassCredentialChecking() {
        // Exempting /users/login from JwtAuthFilter must not be confused
        // with exempting it from actually checking the password —
        // shouldNotFilter only controls whether a Bearer token is
        // required, not what UserSvc.login() does once the request
        // arrives.
        String email = "wrongpass-" + System.nanoTime() + "@example.com";
        rest.postForEntity(
                url("/users/register"),
                Map.of("name", "Wrong Pass", "email", email, "password", "Password1"),
                Map.class);

        ResponseEntity<Map> response = rest.postForEntity(
                url("/users/login"),
                Map.of("email", email, "password", "DefinitelyNotThePassword1"),
                Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
