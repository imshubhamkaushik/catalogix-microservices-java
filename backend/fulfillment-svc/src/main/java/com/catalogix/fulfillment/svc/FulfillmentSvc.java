package com.catalogix.fulfillment.svc;

import com.catalogix.fulfillment.dto.ShipmentResponse;
import com.catalogix.fulfillment.event.OrderConfirmedEvent;
import com.catalogix.fulfillment.model.Shipment;
import com.catalogix.fulfillment.model.ShipmentStatus;
import com.catalogix.fulfillment.repository.ShipmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class FulfillmentSvc {
    private final ShipmentRepository repo;

    public FulfillmentSvc(ShipmentRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void createFromOrder(OrderConfirmedEvent event) {
        if (event == null || event.items() == null) return;

        var sellerIds = event.items().stream()
                .map(item -> item.sellerId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        for (Long sellerId : sellerIds) {
            if (repo.findByOrderIdAndSellerId(event.orderId(), sellerId).isPresent()) continue;

            Shipment shipment = new Shipment();
            shipment.setOrderId(event.orderId());
            shipment.setUserId(event.userId());
            shipment.setSellerId(sellerId);
            shipment.setTrackingNumber(
                    "CAT-" + event.orderId() + "-" + sellerId + "-"
                            + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT));
            repo.save(shipment);
        }
    }

    @Transactional(readOnly = true)
    public List<ShipmentResponse> forCustomer(Long userId) {
        return repo.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ShipmentResponse> forOrder(Long orderId, Long requesterId, String role) {
        var all = repo.findByOrderIdOrderById(orderId);

        if ("ADMIN".equalsIgnoreCase(role)) {
            return all.stream().map(this::toResponse).toList();
        }

        if ("SELLER".equalsIgnoreCase(role)) {
            var sellerShipments = all.stream()
                    .filter(shipment -> shipment.getSellerId().equals(requesterId))
                    .toList();
            if (sellerShipments.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
            }
            return sellerShipments.stream().map(this::toResponse).toList();
        }

        if (!all.stream().anyMatch(shipment -> shipment.getUserId().equals(requesterId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        }

        return all.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ShipmentResponse> all() {
        return repo.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ShipmentResponse> forSeller(Long sellerId) {
        return repo.findBySellerIdOrderByUpdatedAtDesc(sellerId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ShipmentResponse update(Long id, Long requesterId, String role, ShipmentStatus requestedStatus) {
        Shipment shipment = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));

        boolean admin = "ADMIN".equalsIgnoreCase(role);
        boolean owner = "SELLER".equalsIgnoreCase(role) && shipment.getSellerId().equals(requesterId);
        if (!admin && !owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        }

        if (!validTransition(shipment.getStatus(), requestedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid shipment transition");
        }

        shipment.setStatus(requestedStatus);
        Shipment saved = repo.save(shipment);
        return toResponse(saved);
    }

    private boolean validTransition(ShipmentStatus current, ShipmentStatus next) {
        if (current == next) return true;
        return switch (current) {
            case CREATED -> next == ShipmentStatus.PACKED || next == ShipmentStatus.CANCELLED;
            case PACKED -> next == ShipmentStatus.SHIPPED || next == ShipmentStatus.CANCELLED;
            case SHIPPED -> next == ShipmentStatus.OUT_FOR_DELIVERY;
            case OUT_FOR_DELIVERY -> next == ShipmentStatus.DELIVERED;
            default -> false;
        };
    }

    private ShipmentResponse toResponse(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getId(), shipment.getOrderId(), shipment.getSellerId(),
                shipment.getTrackingNumber(), shipment.getStatus(),
                shipment.getCreatedAt(), shipment.getUpdatedAt());
    }
}
