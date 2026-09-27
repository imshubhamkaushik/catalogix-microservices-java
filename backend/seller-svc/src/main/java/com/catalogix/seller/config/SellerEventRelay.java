package com.catalogix.seller.config;

import com.catalogix.seller.event.SellerDomainEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class SellerEventRelay {
  private final RabbitTemplate rabbit;

  public SellerEventRelay(RabbitTemplate r) {
    rabbit = r;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void on(SellerDomainEvent e) {
    rabbit.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, "seller." + e.action(), e);
  }
}
