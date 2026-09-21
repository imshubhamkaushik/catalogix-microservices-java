package com.catalogix.checkout.repository;

import com.catalogix.checkout.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByUserId(Long userId, Pageable pageable);

    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // SELECT ... FOR UPDATE. payOrder and cancelOrder read an order's status, call other
    // services (charge / refund), then write the new status. Without a lock two concurrent
    // requests (double-click, two tabs, a retry racing the original) both read PENDING_PAYMENT
    // and both charge the card. With it the second request blocks here until the first
    // commits, then sees the new status and is rejected by the normal state check.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    // Oldest unpaid orders first; used by PendingOrderExpiryJob.
    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.createdAt < :cutoff ORDER BY o.createdAt")
    java.util.List<Long> findIdsByStatusCreatedBefore(
            @Param("status") com.catalogix.checkout.model.OrderStatus status,
            @Param("cutoff") java.time.Instant cutoff,
            org.springframework.data.domain.Pageable pageable);

    // Backs the "Verified Purchase" badge on reviews (see review-svc's
    // OrderClient) — true only once the order has actually been delivered,
    // not merely placed or paid, matching what Amazon/Flipkart's own badge means.
    @Query("SELECT COUNT(o) > 0 FROM Order o JOIN o.items i " +
           "WHERE o.userId = :userId AND i.productId = :productId AND o.status = 'DELIVERED'")
    boolean existsDeliveredOrderWithProduct(@Param("userId") Long userId, @Param("productId") Long productId);
}
