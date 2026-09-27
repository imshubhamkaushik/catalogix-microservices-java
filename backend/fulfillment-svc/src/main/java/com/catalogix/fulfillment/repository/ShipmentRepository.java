package com.catalogix.fulfillment.repository;

import com.catalogix.fulfillment.model.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    Optional<Shipment> findByOrderIdAndSellerId(Long orderId, Long sellerId);
    List<Shipment> findByOrderIdOrderById(Long orderId);
    List<Shipment> findBySellerIdOrderByUpdatedAtDesc(Long sellerId);
    List<Shipment> findByUserIdOrderByUpdatedAtDesc(Long userId);
    List<Shipment> findAllByOrderByUpdatedAtDesc();
}
