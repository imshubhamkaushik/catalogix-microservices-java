package com.catalogix.catalog.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Verifies JWTs issued by user-svc, and also mints short-lived "system"
 * tokens (see generateSystemToken) for catalog-svc's own calls to
 * inventory-svc's /adjust endpoint, which no longer accepts regular user
 * tokens — see InventoryClient for why.
 *
 * MUST be configured with the same JWT_SECRET as user-svc and every other service.
 */
@Service
public class JwtService {

    private final SecretKey key;

    private static final String EXAMPLE_PLACEHOLDER =
            "change-this-to-a-long-random-string-at-least-32-chars";

    // Sentinel subject/role for internally-minted tokens — never a real user id.
    private static final String SYSTEM_SUBJECT = "0";
    private static final String SYSTEM_ROLE = "SYSTEM";
    private static final long SYSTEM_TOKEN_TTL_MS = 5 * 60 * 1000; // 5 minutes

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

    /** Mints a short-lived token identifying this call as coming from the system itself, not a user. */
    public String generateSystemToken() {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + SYSTEM_TOKEN_TTL_MS);
        return Jwts.builder()
                .subject(SYSTEM_SUBJECT)
                .claim("email", "system@internal")
                .claim("role", SYSTEM_ROLE)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }
}
