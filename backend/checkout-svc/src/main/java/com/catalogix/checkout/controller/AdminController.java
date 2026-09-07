package com.catalogix.checkout.controller;

import com.catalogix.checkout.dto.OutboxEntryResponse;
import com.catalogix.checkout.exception.ForbiddenException;
import com.catalogix.checkout.model.CompensationOutbox;
import com.catalogix.checkout.model.OutboxStatus;
import com.catalogix.checkout.repository.CompensationOutboxRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Operational visibility into the compensation outbox (see
 * CompensationOutboxProcessor) — admin-only. Not a general-purpose admin
 * API; just enough to see what's stuck and give it another push.
 */
@RestController
@RequestMapping("/admin/outbox")
public class AdminController {

    private final CompensationOutboxRepository outboxRepo;

    public AdminController(CompensationOutboxRepository outboxRepo) {
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

    @PostMapping("/{id}/retry")
    public ResponseEntity<OutboxEntryResponse> retry(
            @PathVariable Long id,
            @RequestAttribute("userRole") String role
    ) {
        requireAdmin(role);
        CompensationOutbox entry = outboxRepo.findById(id)
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

    private OutboxEntryResponse toResponse(CompensationOutbox e) {
        return new OutboxEntryResponse(
                e.getId(), e.getType(), e.getProductId(), e.getDelta(), e.getCouponCode(), e.getReason(),
                e.getStatus(), e.getAttempts(), e.getLastError(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
