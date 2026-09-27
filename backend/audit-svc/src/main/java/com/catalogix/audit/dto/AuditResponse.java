package com.catalogix.audit.dto;

import java.time.Instant;

public record AuditResponse(Long id, Long actorUserId, String action, String entityType, String entityId,
    String details, Instant occurredAt) {
}
