package com.catalogix.fulfillment.config;

import com.catalogix.fulfillment.event.OrderConfirmedEvent;
import com.catalogix.fulfillment.svc.FulfillmentSvc;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {
  private final FulfillmentSvc svc;

  public OrderEventListener(FulfillmentSvc s) {
    svc = s;
  }

  @RabbitListener(queues = "fulfillment.order-confirmed")
  public void on(OrderConfirmedEvent e) {
    svc.createFromOrder(e);
  }
}
