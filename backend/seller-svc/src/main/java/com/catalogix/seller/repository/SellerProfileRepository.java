package com.catalogix.seller.repository;

import com.catalogix.seller.model.SellerProfile;
import com.catalogix.seller.model.SellerStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SellerProfileRepository extends JpaRepository<SellerProfile, Long> {
    Optional<SellerProfile> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SellerProfile p where p.userId = :userId")
    Optional<SellerProfile> findByUserIdForUpdate(@Param("userId") Long userId);

    List<SellerProfile> findByStatus(SellerStatus status);
}
