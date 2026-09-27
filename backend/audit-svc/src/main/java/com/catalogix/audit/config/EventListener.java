package com.catalogix.audit.config;

import com.catalogix.audit.svc.AuditSvc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Component
public class EventListener {

  private static final Logger log = LoggerFactory.getLogger(EventListener.class);

  private final AuditSvc svc;
  private final JsonMapper mapper = new JsonMapper();

  public EventListener(AuditSvc svc) {
    this.svc = svc;
  }

  @RabbitListener(queues = "audit.all-events")
  public void on(Message message) {
    try {
      String key = message.getMessageProperties().getReceivedRoutingKey();
      Map<?, ?> payload = mapper.readValue(message.getBody(), Map.class);
      svc.recordAuditEvent(key, payload);
    } catch (RuntimeException ex) {
      // Keep the existing fail-open listener contract, but make failures visible
      // instead of silently acknowledging a message that could not be audited.
      log.error("Failed to process audit event", ex);
    }
  }
}
