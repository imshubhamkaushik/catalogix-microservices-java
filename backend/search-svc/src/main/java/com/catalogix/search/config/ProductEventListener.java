package com.catalogix.search.config;

import com.catalogix.search.event.ProductEvent;
import com.catalogix.search.svc.SearchSvc;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ProductEventListener {
    private final SearchSvc svc;

    public ProductEventListener(SearchSvc svc) {
        this.svc = svc;
    }

    @RabbitListener(queues = "search.product-events")
    public void on(ProductEvent event) {
        svc.apply(event);
    }
}
