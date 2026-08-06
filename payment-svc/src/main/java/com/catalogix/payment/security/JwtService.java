package com.catalogix.payment.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verifies JWTs issued by user-svc (or system tokens minted by checkout-svc
 * for background/compensation calls). payment-svc never issues its own tokens,
 * only validates the ones it's handed — hence no generateToken() here.
 *
 * MUST be configured with the same JWT_SECRET as every other service.
 */
@Component
public class JwtService {

    private final SecretKey key;

    private static final String EXAMPLE_PLACEHOLDER =
            "change-this-to-a-long-random-string-at-least-32-chars";

    public JwtService(@Value("${JWT_SECRET}") String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                "JWT_SECRET must be set and at least 32 characters long (HS256 requires a 256-bit key)");
        }
        if (EXAMPLE_PLACEHOLDER.equals(secret)) {
            throw new IllegalStateException(
                "JWT_SECRET is still set to the placeholder value from .env.example — "
                + "generate a real one, e.g. `openssl rand -base64 48`");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws JwtException if the token is malformed, expired, or the signature does not match.
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
