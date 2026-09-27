package com.catalogix.promotions.repository;

import com.catalogix.promotions.model.CouponRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, String> {
}
