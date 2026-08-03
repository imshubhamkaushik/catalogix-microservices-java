package com.catalogix.order.svc;

import com.catalogix.order.config.RabbitMQConfig;
import com.catalogix.order.event.OrderCancelledEvent;
import com.catalogix.order.event.OrderConfirmedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Relays OrderSvc's in-JVM events to RabbitMQ for notification-svc to consume.
 *
 * Two things worth calling out:
 * - @TransactionalEventListener(phase = AFTER_COMMIT) means the RabbitMQ
 *   publish only happens once the order's DB transaction has actually
 *   committed — an event published mid-transaction that then rolled back
 *   would otherwise tell the world about an order that doesn't exist.
 * - This is deliberately simpler than the stock_adjustment_outbox pattern
 *   used elsewhere in this service: it guarantees correct *ordering* relative
 *   to the DB write, but NOT delivery if RabbitMQ happens to be unreachable
 *   at the moment of publish — there's no retry/outbox table backing this.
 *   That's an acceptable trade for "send a confirmation email" (best-effort
 *   by nature already) but would need the heavier pattern for anything where
 *   losing an event silently is unacceptable.
 * - A separate bean from OrderSvc on purpose: @TransactionalEventListener and
 *   @Async are both AOP-proxy-based, so (like @Transactional/@CircuitBreaker
 *   elsewhere in this service) they only take effect on calls arriving via
 *   the proxy — which for event listeners means "published through
 *   ApplicationEventPublisher.publishEvent(...)", not a direct method call.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        publish("order.confirmed", event, event.orderId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCancelled(OrderCancelledEvent event) {
        publish("order.cancelled", event, event.orderId());
    }

    private void publish(String routingKey, Object event, Long orderId) {
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, routingKey, event);
        } catch (Exception e) {
            log.warn("Failed to publish {} for order {}: {}", routingKey, orderId, e.getMessage());
        }
    }
}
