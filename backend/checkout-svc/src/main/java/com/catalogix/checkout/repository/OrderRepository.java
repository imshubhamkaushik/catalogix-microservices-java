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

    // SELECT ... FOR UPDATE, for SHORT transactions only (read status -> write status). payOrder
    // uses it to atomically move PENDING_PAYMENT -> PAYMENT_PROCESSING and, after the payment
    // call, to settle the result — it is NOT held while payment-svc is being called. Two
    // concurrent pay requests (double-click, two tabs, a retry racing the original) serialise
    // on this lock for microseconds; the loser then sees PAYMENT_PROCESSING and is rejected.
    // cancelOrder / expireUnpaidOrder still call other services while holding it (see the
    // notes on those methods).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    // Oldest unpaid orders first; used by PendingOrderExpiryJob. An order whose customer tried to
    // pay recently (payment_started_at) is not expired on its creation time alone — the
    // payment window restarts with each attempt.
    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.createdAt < :cutoff "
            + "AND (o.paymentStartedAt IS NULL OR o.paymentStartedAt < :cutoff) ORDER BY o.createdAt")
    java.util.List<Long> findIdsByStatusCreatedBefore(
            @Param("status") com.catalogix.checkout.model.OrderStatus status,
            @Param("cutoff") java.time.Instant cutoff,
            org.springframework.data.domain.Pageable pageable);

    // Orders claimed for payment (PAYMENT_PROCESSING) long enough ago that the request which
    // claimed them must have died (pod crash / deploy) — see PendingOrderExpiryJob.
    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.paymentStartedAt < :cutoff "
            + "ORDER BY o.paymentStartedAt")
    java.util.List<Long> findIdsByStatusPaymentStartedBefore(
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
