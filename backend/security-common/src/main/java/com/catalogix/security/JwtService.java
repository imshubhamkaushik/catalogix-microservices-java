package com.catalogix.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Verifies HS256 JWTs issued by user-svc (real user sessions) or minted as
 * short-lived "system" tokens by any service that needs to make an
 * authenticated call with no inbound user request to forward a token from
 * (e.g. catalog-svc calling inventory-svc's /adjust endpoint, or
 * checkout-svc's background compensation outbox processor).
 *
 * Previously this class (and JwtAuthFilter/RateLimiterFilter/CorsConfig)
 * existed as 9 separate, hand-copied files — one per service package — kept
 * deliberately duplicated rather than shared, on the reasoning that each
 * service should own its code independently. In practice this meant a fix
 * or CVE patch here had to be found and applied 9 times by hand, with
 * nothing enforcing the copies stayed in sync (and generateSystemToken()
 * had in fact already drifted: catalog-svc and checkout-svc each carried an
 * identical copy of it, and user-svc's own version used a different sentinel
 * email claim, "user-svc@internal", inconsistent with the "system@internal"
 * convention used by the other services
 * expected). Consolidating the genuinely-identical parts here removes that
 * drift risk; only real per-user token issuance (see user-svc's JwtService,
 * which extends this class) stays service-specific, because it's the one
 * piece that's actually different per service, not just copy-pasted.
 *
 * MUST be configured with the same JWT_SECRET as every other service —
 * HS256/HMAC is symmetric, so anyone holding the secret can both sign and
 * verify. No service needs to know or care which other service minted a
 * given token, only that its signature is valid.
 */
@Service
public class JwtService {

    private final SecretKey key;

    private static final String EXAMPLE_PLACEHOLDER =
            "change-this-to-a-long-random-string-at-least-32-chars";

    // Sentinel subject/role/email for internally-minted tokens — never a real
    // user id. Fixed, not per-service, deliberately: the receiving service
    // only checks role == SYSTEM, never which service minted the token.
    private static final String SYSTEM_SUBJECT = "0";
    private static final String SYSTEM_ROLE = "SYSTEM";
    private static final String SYSTEM_EMAIL = "system@internal";
    private static final Duration SYSTEM_TOKEN_TTL = Duration.ofMinutes(5); // 5 minutes

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
        Instant now = Instant.now();
        Instant expiry = now.plus(SYSTEM_TOKEN_TTL);
        return Jwts.builder()
                .subject(SYSTEM_SUBJECT)
                .claim("email", SYSTEM_EMAIL)
                .claim("role", SYSTEM_ROLE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    /**
     * Exposed so user-svc's subclass can mint real, per-user tokens with the
     * same key without this class needing to know anything about users,
     * emails, or roles beyond the generic system sentinel above.
     */
    protected SecretKey getKey() {
        return key;
    }
}
