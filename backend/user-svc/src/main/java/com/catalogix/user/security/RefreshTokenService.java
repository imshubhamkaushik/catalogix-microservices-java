package com.catalogix.user.security;

import com.catalogix.user.exception.ForbiddenException;
import com.catalogix.user.exception.UnauthorizedException;
import com.catalogix.user.model.RefreshToken;
import com.catalogix.user.repository.RefreshTokenRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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
        return issue(userId, null);
    }

    @Transactional
    public String issue(Long userId, String userAgent) {
        String rawToken = tokenHasher.generateRawToken();
        RefreshToken entity = new RefreshToken(
                userId, tokenHasher.hash(rawToken), Instant.now().plusMillis(expirationMs), userAgent);
        repo.save(entity);
        return rawToken;
    }

    /**
     * Validates the raw token, revokes it, and issues a fresh replacement for
     * the same user — the standard "rotate on use" pattern. The new token
     * carries forward the same user agent as the one being rotated (it's the
     * same physical device continuing to refresh, not a new login), so
     * callers don't need to re-supply it on every refresh call.
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

        String newToken = issue(existing.getUserId(), existing.getUserAgent());
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

    // For the session-list UI. Only returns sessions that are actually still
    // usable (not revoked, not expired) — a revoked/expired row isn't a
    // "session" from the user's point of view, it's just historical noise.
    @Transactional(readOnly = true)
    public List<RefreshToken> listActiveSessions(Long userId) {
        Instant now = Instant.now();
        return repo.findByUserIdAndRevokedFalseOrderByLastUsedAtDesc(userId)
                .stream()
                .filter(t -> t.isValid(now))
                .toList();
    }

    /**
     * Revokes one specific session by its own database id — for "log out
     * this one device" rather than logout()'s "log out the device making
     * this request" or revokeAllForUser's "log out everywhere".
     *
     * @throws com.catalogix.user.exception.ForbiddenException if the session belongs to a different user
     *         (never leaks whether the id exists at all to someone who doesn't own it)
     */
    @Transactional
    public void revokeById(Long sessionId, Long requesterId) {
        repo.findById(sessionId).ifPresent(session -> {
            if (!session.getUserId().equals(requesterId)) {
                throw new ForbiddenException("You may only revoke your own sessions");
            }
            session.setRevoked(true);
            repo.save(session);
        });
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public record RotationResult(Long userId, String newRefreshToken) {
    }
}
