package com.catalogix.promotions.repository;

import com.catalogix.promotions.model.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    // Unlocked read — fine for admin listing and for the read-only /preview
    // endpoint, which never mutates usedCount.
    Optional<Coupon> findByCodeIgnoreCase(String code);

    // Row-locked read used by commit()/release(), the two operations that
    // actually mutate usedCount. This is the fix for the coupon
    // over-redemption race the original audit found: validate() and
    // recordUsage() used to be two separate, unlocked calls with a gap
    // between them, so two concurrent checkouts could both read
    // usedCount < maxUses and both increment past it. Locking the row for
    // the entire check-then-increment closes that window, the same way
    // inventory's findByProductIdForUpdate already protected stock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE LOWER(c.code) = LOWER(:code)")
    Optional<Coupon> findByCodeIgnoreCaseForUpdate(@Param("code") String code);
}
