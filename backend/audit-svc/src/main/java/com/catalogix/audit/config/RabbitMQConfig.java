package com.catalogix.audit.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.*;

@Configuration
public class RabbitMQConfig {
  @Bean
  TopicExchange eventsExchange() {
    return new TopicExchange("catalogix.events", true, false);
  }

  @Bean
  MessageConverter json() {
    return new JacksonJsonMessageConverter();
  }

  @Bean
  Queue q() {
    return QueueBuilder.durable("audit.all-events").quorum().build();
  }

  @Bean
  Binding order() {
    return BindingBuilder.bind(q()).to(eventsExchange()).with("order.#");
  }

  @Bean
  Binding product() {
    return BindingBuilder.bind(q()).to(eventsExchange()).with("product.#");
  }

  @Bean
  Binding seller() {
    return BindingBuilder.bind(q()).to(eventsExchange()).with("seller.#");
  }

  @Bean
  Binding payout() {
    return BindingBuilder.bind(q()).to(eventsExchange()).with("payout.#");
  }

  @Bean
  Binding user() {
    return BindingBuilder.bind(q()).to(eventsExchange()).with("user.#");
  }

  @Bean
  Binding fulfillment() {
    return BindingBuilder.bind(q()).to(eventsExchange()).with("fulfillment.#");
  }

  @Bean
  Binding returns() {
    return BindingBuilder.bind(q()).to(eventsExchange()).with("return.#");
  }
}
