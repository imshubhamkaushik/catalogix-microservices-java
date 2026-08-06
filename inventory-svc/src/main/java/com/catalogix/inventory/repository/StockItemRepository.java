package com.catalogix.inventory.repository;

import com.catalogix.inventory.model.StockItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StockItemRepository extends JpaRepository<StockItem, Long> {

    // Row-locked read used for every adjustment, so two concurrent
    // reservations for the same product can't both read a stale quantity
    // and both succeed when only one of them should.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockItem s WHERE s.productId = :productId")
    Optional<StockItem> findByProductIdForUpdate(@Param("productId") Long productId);
}
