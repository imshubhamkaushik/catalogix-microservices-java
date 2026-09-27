package com.catalogix.checkout.repository;

import com.catalogix.checkout.model.ReturnRequest;
import com.catalogix.checkout.model.ReturnStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {

    List<ReturnRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<ReturnRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ReturnRequest> findByStatusOrderByCreatedAtDesc(ReturnStatus status, Pageable pageable);

    // Used to compute "how much of this product on this order has already
    // been returned" when validating a new return request — REQUESTED and
    // REFUNDED both count as consuming return allowance (a pending request
    // shouldn't let the same units be double-requested before it's even
    // decided); REJECTED doesn't, since nothing was actually returned.
    List<ReturnRequest> findByOrderIdAndStatusIn(Long orderId, List<ReturnStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReturnRequest r WHERE r.id = :id")
    Optional<ReturnRequest> findByIdForUpdate(@Param("id") Long id);
}
