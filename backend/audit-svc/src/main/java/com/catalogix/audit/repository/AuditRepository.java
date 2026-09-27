package com.catalogix.audit.repository;

import com.catalogix.audit.model.AuditEntry;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {
  Page<AuditEntry> findAllByOrderByOccurredAtDesc(Pageable pageable);
}
