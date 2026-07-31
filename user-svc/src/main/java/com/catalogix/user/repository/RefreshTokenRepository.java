package com.catalogix.user.repository;

import com.catalogix.user.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revoked = true WHERE t.userId = :userId AND t.revoked = false")
    int revokeAllForUser(@Param("userId") Long userId);

    // Housekeeping: delete anything that's long past useful (expired or
    // revoked a while ago), so the table doesn't grow unbounded forever.
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff OR (t.revoked = true AND t.createdAt < :cutoff)")
    int deleteStaleBefore(@Param("cutoff") Instant cutoff);
}
