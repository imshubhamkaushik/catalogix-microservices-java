package com.catalogix.recommendation.config;

import com.catalogix.recommendation.event.OrderConfirmedEvent;
import com.catalogix.recommendation.svc.RecommendationSvc;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {
  final RecommendationSvc svc;

  public OrderEventListener(RecommendationSvc s) {
    svc = s;
  }

  @RabbitListener(queues = "recommendation.order-confirmed")
  public void on(OrderConfirmedEvent e) {
    svc.ingest(e);
  }
}
