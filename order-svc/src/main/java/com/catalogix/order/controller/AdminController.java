package com.catalogix.order.controller;

import com.catalogix.order.dto.OutboxEntryResponse;
import com.catalogix.order.exception.ForbiddenException;
import com.catalogix.order.model.OutboxStatus;
import com.catalogix.order.model.StockAdjustmentOutbox;
import com.catalogix.order.repository.StockAdjustmentOutboxRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Operational visibility into the stock-adjustment outbox (see
 * StockAdjustmentOutboxProcessor) — admin-only. Not a general-purpose admin
 * API; just enough to see what's stuck and give it another push.
 */
@RestController
@RequestMapping("/admin/outbox")
public class AdminController {

    private final StockAdjustmentOutboxRepository outboxRepo;

    public AdminController(StockAdjustmentOutboxRepository outboxRepo) {
        this.outboxRepo = outboxRepo;
    }

    @GetMapping
    public List<OutboxEntryResponse> list(@RequestAttribute("userRole") String role) {
        requireAdmin(role);
        return outboxRepo.findByStatusInOrderByCreatedAtDesc(List.of(OutboxStatus.PENDING, OutboxStatus.DEAD_LETTER))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // Resets a dead-lettered entry back to PENDING with a clean attempt count,
    // so the next scheduled run picks it up again.
    @PostMapping("/{id}/retry")
    public ResponseEntity<OutboxEntryResponse> retry(
            @PathVariable Long id,
            @RequestAttribute("userRole") String role
    ) {
        requireAdmin(role);
        StockAdjustmentOutbox entry = outboxRepo.findById(Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("Outbox entry not found: " + id));
        entry.setStatus(OutboxStatus.PENDING);
        entry.setAttempts(0);
        entry.setLastError(null);
        entry.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(toResponse(outboxRepo.save(entry)));
    }

    private void requireAdmin(String role) {
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new ForbiddenException("Admin access required");
        }
    }

    private OutboxEntryResponse toResponse(StockAdjustmentOutbox e) {
        return new OutboxEntryResponse(
                e.getId(), e.getProductId(), e.getDelta(), e.getReason(), e.getStatus(),
                e.getAttempts(), e.getLastError(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
