package com.catalogix.audit.svc;

import com.catalogix.audit.model.AuditEntry;
import com.catalogix.audit.repository.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Service
public class AuditSvc {
  private static final Logger log = LoggerFactory.getLogger(AuditSvc.class);

  private final AuditRepository repo;
  private final JsonMapper mapper = new JsonMapper();

  public AuditSvc(AuditRepository r) {
    repo = r;
  }

  @Transactional
  public void recordAuditEvent(String routingKey, Map<?, ?> payload) {
    AuditEntry e = new AuditEntry();

    e.setAction(
        routingKey.toUpperCase(Locale.ROOT)
        .replace('.', '_')
        .replace('-', '_'));
    String type = routingKey.contains(".") ? routingKey.substring(0, routingKey.indexOf('.')) : routingKey;
    e.setEntityType(type);

    Object id = payload.get("orderId");

    if (id == null)
      id = payload.get("id");

    if (id == null)
      id = payload.get("sellerId");

    e.setEntityId(id == null ? null : String.valueOf(id));

    Object uid = payload.get("userId");

    if (uid instanceof Number n)
      e.setActorUserId(n.longValue());

    Object ts = payload.get("occurredAt");

    if (ts instanceof String timestamp) {
      try {
        e.setOccurredAt(Instant.parse(timestamp));
      } catch (RuntimeException ex) {
        log.debug("Ignoring invalid event timestamp '{}'", timestamp);
      }
    }

    try {
      String d = mapper.writeValueAsString(payload);
      e.setDetails(d.length() > 1000 ? d.substring(0, 1000) : d);
    } catch (RuntimeException ex) {
      log.warn("Could not serialize audit event payload: {}", ex.getMessage());
      e.setDetails("payload-unavailable");
    }
    repo.save(e);
  }
}
