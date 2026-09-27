package com.catalogix.catalog.config;

import com.catalogix.catalog.event.ProductDomainEvent;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.*;
import org.springframework.transaction.event.*;

@Configuration
public class RabbitMQEventConfig {
  public static final String EX = "catalogix.events";

  @Bean
  TopicExchange productEventsExchange() {
    return new TopicExchange(EX, true, false);
  }

  @Bean
  MessageConverter productJsonConverter() {
    return new JacksonJsonMessageConverter();
  }

  @Bean
  ProductEventRelay productEventRelay(RabbitTemplate rabbitTemplate) {
    return new ProductEventRelay(rabbitTemplate);
  }

  @Bean
  org.springframework.amqp.core.Queue sellerStatusQueue() {
    return org.springframework.amqp.core.QueueBuilder.durable("catalog.seller-status").quorum().build();
  }

  @Bean
  org.springframework.amqp.core.Binding sellerStatusBinding(org.springframework.amqp.core.Queue sellerStatusQueue,
      TopicExchange productEventsExchange) {
    return org.springframework.amqp.core.BindingBuilder.bind(sellerStatusQueue).to(productEventsExchange)
        .with("seller.status-changed");
  }

  public static class ProductEventRelay {
    private final RabbitTemplate rabbitTemplate;

    public ProductEventRelay(RabbitTemplate r) {
      rabbitTemplate = r;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void relay(ProductDomainEvent e) {
      rabbitTemplate.convertAndSend(EX, e.deleted() ? "product.deleted" : "product.changed", e);
    }
  }
}
