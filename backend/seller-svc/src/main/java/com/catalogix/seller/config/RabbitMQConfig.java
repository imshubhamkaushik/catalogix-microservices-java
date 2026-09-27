package com.catalogix.seller.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String EVENTS_EXCHANGE = "catalogix.events";
    @Bean public TopicExchange eventsExchange() { return new TopicExchange(EVENTS_EXCHANGE, true, false); }
    @Bean public MessageConverter jsonMessageConverter() { return new JacksonJsonMessageConverter(); }
    @Bean public Queue orderConfirmedQueue() { return QueueBuilder.durable("seller.order-confirmed").quorum().build(); }
    @Bean public Binding orderConfirmedBinding() { return BindingBuilder.bind(orderConfirmedQueue()).to(eventsExchange()).with("order.confirmed"); }
    @Bean public Queue orderCancelledQueue() { return QueueBuilder.durable("seller.order-cancelled").quorum().build(); }
    @Bean public Binding orderCancelledBinding() { return BindingBuilder.bind(orderCancelledQueue()).to(eventsExchange()).with("order.cancelled"); }
    @Bean public Queue returnRefundedQueue() { return QueueBuilder.durable("seller.return-refunded").quorum().build(); }
    @Bean public Binding returnRefundedBinding() { return BindingBuilder.bind(returnRefundedQueue()).to(eventsExchange()).with("return.refunded"); }
}
