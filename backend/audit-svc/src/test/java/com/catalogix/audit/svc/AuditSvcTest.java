package com.catalogix.audit.svc;

import com.catalogix.audit.model.AuditEntry;
import com.catalogix.audit.repository.AuditRepository;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import static org.mockito.Mockito.*;

class AuditSvcTest {
  @Mock
  AuditRepository repo;
  AuditSvc svc;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    svc = new AuditSvc(repo);
  }

  @Test
  void recordNormalizesRoutingKeyAndActor() {
    svc.recordAuditEvent("seller.status-changed", Map.of("sellerId", 9, "userId", 42, "status", "APPROVED"));
    verify(repo).save(argThat(x -> {
      AuditEntry e = (AuditEntry) x;
      return e.getAction().equals("SELLER_STATUS-CHANGED".replace('-', '_')) && e.getEntityId().equals("9")
          && e.getActorUserId().equals(42L);
    }));
  }
}
