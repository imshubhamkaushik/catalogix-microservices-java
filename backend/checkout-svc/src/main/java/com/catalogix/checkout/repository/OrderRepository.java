package com.catalogix.checkout.repository;

import com.catalogix.checkout.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByUserId(Long userId, Pageable pageable);

    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // Backs the "Verified Purchase" badge on reviews (see review-svc's
    // OrderClient) — true only once the order has actually been delivered,
    // not merely placed or paid, matching what Amazon/Flipkart's own badge means.
    @Query("SELECT COUNT(o) > 0 FROM Order o JOIN o.items i " +
           "WHERE o.userId = :userId AND i.productId = :productId AND o.status = 'DELIVERED'")
    boolean existsDeliveredOrderWithProduct(@Param("userId") Long userId, @Param("productId") Long productId);
}
