package com.catalogix.user.repository;

import com.catalogix.user.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // For the session-list UI (see RefreshTokenService.listActiveSessions).
    // Expiry is checked in the service layer (isValid()) rather than here,
    // same as everywhere else this entity is used — one definition of
    // "valid" instead of two that could drift out of sync.
    List<RefreshToken> findByUserIdAndRevokedFalseOrderByLastUsedAtDesc(Long userId);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revoked = true WHERE t.userId = :userId AND t.revoked = false")
    int revokeAllForUser(@Param("userId") Long userId);

    // Every session of the user EXCEPT the one identified by keepHash — "sign out my
    // other devices" (e.g. after a password change) without ending this one.
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revoked = true "
            + "WHERE t.userId = :userId AND t.revoked = false AND t.tokenHash <> :keepHash")
    int revokeAllForUserExcept(@Param("userId") Long userId, @Param("keepHash") String keepHash);

    // Atomic "use this token exactly once": a single conditional UPDATE. When two
    // requests present the same refresh token at the same moment, the database
    // serialises them — one updates the row (returns 1), the other finds
    // revoked = true already and updates nothing (returns 0). The old
    // read-check-then-save let both succeed and mint two replacement tokens.
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revoked = true WHERE t.id = :id AND t.revoked = false")
    int revokeIfActive(@Param("id") Long id);

    // Housekeeping: delete anything that's long past useful (expired or
    // revoked a while ago), so the table doesn't grow unbounded forever.
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff OR (t.revoked = true AND t.createdAt < :cutoff)")
    int deleteStaleBefore(@Param("cutoff") Instant cutoff);
}
