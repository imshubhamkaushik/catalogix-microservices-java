package com.catalogix.seller.repository;

import com.catalogix.seller.model.Payout;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface PayoutRepository extends JpaRepository<Payout, Long> {
  List<Payout> findBySellerUserIdOrderByCreatedAtDesc(Long userId);

  Optional<Payout> findByIdempotencyKey(String idempotencyKey);
}
