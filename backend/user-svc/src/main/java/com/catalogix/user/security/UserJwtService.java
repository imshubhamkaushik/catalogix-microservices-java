package com.catalogix.user.security;

import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.time.Instant;

/**
 * Extends the shared com.catalogix.security.JwtService with the one thing
 * that's genuinely unique to user-svc: minting real, per-user access tokens
 * with a configurable expiry. Parsing (parseClaims) and system-token minting
 * (generateSystemToken) are inherited as-is — they were previously
 * hand-copied identically across all 9 services (and had in fact already
 * drifted slightly: this class used to mint its own system tokens with a
 * different sentinel email, "user-svc@internal", instead of the
 * "system@internal" convention used by the other services
 * tests already expected — using the inherited version fixes that
 * inconsistency as a side effect, not just removes duplication).
 */
@Service
public class UserJwtService extends com.catalogix.security.JwtService {

    private final long expirationMs;

    public UserJwtService(
            @Value("${JWT_SECRET}") String secret,
            @Value("${JWT_EXPIRATION_MS:900000}") long expirationMs
    ) {
        super(secret);
        this.expirationMs = expirationMs;
    }

    public String generateToken(Long userId, String email, String role) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(expirationMs);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Date.from(now)) // NOSONAR java:S2143
                .expiration(Date.from(expiry)) // NOSONAR java:S2143
                .signWith(getKey())
                .compact();
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
