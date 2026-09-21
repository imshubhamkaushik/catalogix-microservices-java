package com.catalogix.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JWT key rotation: JWT_SECRET_PREVIOUS keeps tokens signed with the old secret valid. */
class JwtServiceRotationTest {

    private static final String OLD_SECRET = "old-test-only-secret-at-least-32-characters-long";
    private static final String NEW_SECRET = "new-test-only-secret-at-least-32-characters-long";
    private static final String OTHER_SECRET = "other-test-only-secret-at-least-32-characters-long";

    @Test
    void acceptsTokenSignedWithCurrentSecret() {
        JwtService service = new JwtService(NEW_SECRET, OLD_SECRET);

        Claims claims = service.parseClaims(service.generateSystemToken());

        assertThat(claims.get("role", String.class)).isEqualTo("SYSTEM");
    }

    @Test
    void acceptsTokenSignedWithPreviousSecret_whileRotating() {
        String tokenFromOldKey = new JwtService(OLD_SECRET).generateSystemToken();
        JwtService rotated = new JwtService(NEW_SECRET, OLD_SECRET);

        assertThat(rotated.parseClaims(tokenFromOldKey).getSubject()).isEqualTo("0");
    }

    @Test
    void rejectsTokenSignedWithPreviousSecret_onceRotationIsFinished() {
        String tokenFromOldKey = new JwtService(OLD_SECRET).generateSystemToken();
        JwtService afterRotation = new JwtService(NEW_SECRET, "");

        assertThatThrownBy(() -> afterRotation.parseClaims(tokenFromOldKey)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithUnknownSecret_evenWhileRotating() {
        String foreignToken = new JwtService(OTHER_SECRET).generateSystemToken();
        JwtService rotated = new JwtService(NEW_SECRET, OLD_SECRET);

        assertThatThrownBy(() -> rotated.parseClaims(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void newTokensAreNeverSignedWithThePreviousSecret() {
        String token = new JwtService(NEW_SECRET, OLD_SECRET).generateSystemToken();

        // A service that only knows the OLD secret must not be able to verify it.
        assertThatThrownBy(() -> new JwtService(OLD_SECRET).parseClaims(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTooShortPreviousSecret() {
        assertThatThrownBy(() -> new JwtService(NEW_SECRET, "short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET_PREVIOUS");
    }
}
