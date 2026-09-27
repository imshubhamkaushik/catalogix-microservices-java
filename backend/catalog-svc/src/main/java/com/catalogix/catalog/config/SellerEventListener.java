package com.catalogix.catalog.config;

import com.catalogix.catalog.event.ProductDomainEvent;
import com.catalogix.catalog.event.SellerStatusEvent;
import com.catalogix.catalog.model.Product.ModerationStatus;
import com.catalogix.catalog.repository.ProductRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SellerEventListener {
    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    public SellerEventListener(ProductRepository products, ApplicationEventPublisher events) {
        this.products = products;
        this.events = events;
    }

    @RabbitListener(queues = "catalog.seller-status")
    @Transactional
    public void onSellerStatusChanged(SellerStatusEvent event) {
        if (event == null || event.userId() == null || event.status() == null) return;
        if (!"SUSPENDED".equalsIgnoreCase(event.status())) return;

        products.findByOwnerId(event.userId()).stream()
                .filter(product -> product.getModerationStatus() == ModerationStatus.PUBLISHED)
                .forEach(product -> {
                    product.setModerationStatus(ModerationStatus.SUSPENDED);
                    ProductDomainEvent update = new ProductDomainEvent(
                            product.getId(), product.getName(), product.getDescription(), product.getPrice(),
                            product.getCategory(), product.getOwnerId(), product.getImageUrl(),
                            product.getModerationStatus().name(), false, java.time.Instant.now());
                    products.save(product);
                    events.publishEvent(update);
                });
    }
}
