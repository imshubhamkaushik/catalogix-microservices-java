package com.catalogix.user.security;

import com.catalogix.user.exception.UnauthorizedException;
import com.catalogix.user.model.RefreshToken;
import com.catalogix.user.repository.RefreshTokenRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Issues and validates refresh tokens. A refresh token is a long, random,
 * opaque string (not a JWT — it carries no claims, so revoking it just means
 * deleting/flagging the DB row, unlike a JWT which stays valid until it
 * naturally expires). Only its hash (see TokenHasher) is ever persisted.
 *
 * Tokens are rotated on every refresh: each use revokes the old token and
 * issues a new one, so a stolen-then-reused old token is easy to notice
 * (its hash will already be marked revoked).
 */
@Component
public class RefreshTokenService {

    private final RefreshTokenRepository repo;
    private final TokenHasher tokenHasher;
    private final long expirationMs;

    public RefreshTokenService(
            RefreshTokenRepository repo,
            TokenHasher tokenHasher,
            @Value("${REFRESH_TOKEN_EXPIRATION_MS:604800000}") long expirationMs
    ) {
        this.repo = repo;
        this.tokenHasher = tokenHasher;
        this.expirationMs = expirationMs;
    }

    @Transactional
    public String issue(Long userId) {
        String rawToken = tokenHasher.generateRawToken();
        RefreshToken entity = new RefreshToken(
                userId, tokenHasher.hash(rawToken), Instant.now().plusMillis(expirationMs));
        repo.save(entity);
        return rawToken;
    }

    /**
     * Validates the raw token, revokes it, and issues a fresh replacement for
     * the same user — the standard "rotate on use" pattern.
     *
     * @throws UnauthorizedException if the token is unknown, expired, or already revoked.
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken existing = repo.findByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (!existing.isValid(Instant.now())) {
            throw new UnauthorizedException("Refresh token expired or already used");
        }

        existing.setRevoked(true);
        repo.save(existing);

        String newToken = issue(existing.getUserId());
        return new RotationResult(existing.getUserId(), newToken);
    }

    @Transactional
    public void revoke(String rawToken) {
        repo.findByTokenHash(tokenHasher.hash(rawToken)).ifPresent(t -> {
            t.setRevoked(true);
            repo.save(t);
        });
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        repo.revokeAllForUser(userId);
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public record RotationResult(Long userId, String newRefreshToken) {
    }
}
