package com.catalogix.audit.service;

import com.catalogix.audit.model.AuditEntry;
import com.catalogix.audit.repository.AuditRepository;
import org.junit.jupiter.api.*; import org.mockito.*;
import java.util.*;
import static org.mockito.ArgumentMatchers.any; import static org.mockito.Mockito.*;

class AuditServiceTest {
 @Mock AuditRepository repo; AuditService service;
 @BeforeEach void setUp(){MockitoAnnotations.openMocks(this);service=new AuditService(repo);}
 @Test void recordNormalizesRoutingKeyAndActor(){service.record("seller.status-changed",Map.of("sellerId",9,"userId",42,"status","APPROVED"));verify(repo).save(argThat(x->{AuditEntry e=(AuditEntry)x;return e.getAction().equals("SELLER_STATUS-CHANGED".replace('-','_'))&&e.getEntityId().equals("9")&&e.getActorUserId().equals(42L);}));}
}
