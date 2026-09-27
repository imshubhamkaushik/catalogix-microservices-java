package com.catalogix.seller.config;

import com.catalogix.seller.event.OrderCancelledEvent;
import com.catalogix.seller.event.OrderConfirmedEvent;
import com.catalogix.seller.event.ReturnRefundedEvent;
import com.catalogix.seller.svc.SellerSvc;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {
    private final SellerSvc svc;

    public OrderEventListener(SellerSvc s) {
        svc = s;
    }

    @RabbitListener(queues = "seller.order-confirmed")
    public void onOrderConfirmed(OrderConfirmedEvent e) {
        svc.creditSale(e);
    }

    @RabbitListener(queues = "seller.order-cancelled")
    public void onOrderCancelled(OrderCancelledEvent e) {
        svc.reverseSale(e);
    }

    @RabbitListener(queues = "seller.return-refunded")
    public void onReturnRefunded(ReturnRefundedEvent e) {
        svc.reverseSale(e);
    }
}
