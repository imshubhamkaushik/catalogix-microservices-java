package com.catalogix.order.repository;

import com.catalogix.order.model.OutboxStatus;
import com.catalogix.order.model.StockAdjustmentOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockAdjustmentOutboxRepository extends JpaRepository<StockAdjustmentOutbox, Long> {
    List<StockAdjustmentOutbox> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    List<StockAdjustmentOutbox> findByStatusInOrderByCreatedAtDesc(List<OutboxStatus> statuses);
}
