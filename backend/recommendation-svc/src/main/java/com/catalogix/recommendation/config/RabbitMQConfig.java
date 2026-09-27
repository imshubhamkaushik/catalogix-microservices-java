package com.catalogix.recommendation.config;

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
    return QueueBuilder.durable("recommendation.order-confirmed").quorum().build();
  }

  @Bean
  Binding b() {
    return BindingBuilder.bind(q()).to(eventsExchange()).with("order.confirmed");
  }
}
