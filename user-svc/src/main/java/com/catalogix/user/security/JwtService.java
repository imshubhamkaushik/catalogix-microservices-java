package com.catalogix.user.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Issues and verifies the HS256 JWTs that represent an authenticated session.
 *
 * NOTE: product-svc and order-svc carry their own copy of this class (each
 * service owns its code independently rather than sharing a library module),
 * but all three MUST be configured with the same JWT_SECRET so a token minted
 * here by user-svc verifies successfully there. That shared secret is the
 * only coupling between the services.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    private static final String EXAMPLE_PLACEHOLDER =
            "change-this-to-a-long-random-string-at-least-32-chars";

    public JwtService(
            @Value("${JWT_SECRET}") String secret,
            @Value("${JWT_EXPIRATION_MS:900000}") long expirationMs) {
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
        this.expirationMs = expirationMs;
    }

    public String generateToken(Long userId, String email, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    // Sentinel identity for internally-minted tokens — never a real user id.
    private static final String SYSTEM_SUBJECT = "0";
    private static final long SYSTEM_TOKEN_TTL_MS = 5 * 60 * 1000; // 5 minutes

    /**
     * Mints a short-lived token identifying a call as coming from user-svc
     * itself, not a user's session — used when calling notification-svc for
     * password-reset/verification emails, which happen before (or entirely
     * outside of) any user session existing.
     */
    public String generateSystemToken() {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + SYSTEM_TOKEN_TTL_MS);
        return Jwts.builder()
                .subject(SYSTEM_SUBJECT)
                .claim("email", "system@internal")
                .claim("role", "SYSTEM")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    /**
     * Parses and validates the token's signature and expiry.
     *
     * @throws JwtException if the token is malformed, expired, or the
     *                       signature does not match.
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
