package com.catalogix.search.config;
import com.catalogix.search.event.ProductEvent;
import com.catalogix.search.service.SearchService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
@Component
public class ProductEventListener {
    private final SearchService service;
    public ProductEventListener(SearchService service){this.service=service;}
    @RabbitListener(queues="search.product-events")
    public void on(ProductEvent event){ service.apply(event); }
}
