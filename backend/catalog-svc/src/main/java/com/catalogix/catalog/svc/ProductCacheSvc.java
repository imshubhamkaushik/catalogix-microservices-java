package com.catalogix.catalog.svc;

import com.catalogix.catalog.repository.ProductRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

@Service
public class ProductCacheSvc {

    private final ProductRepository repo;

    public ProductCacheSvc(ProductRepository repo) {
        this.repo = repo;
    }

    @Cacheable(
            value = "products",
            key = "#id",
            unless = "#result == null || #result.isEmpty()"
    )
    @Transactional(readOnly = true)
    public Optional<ProductCore> cacheCore(long id) {
        return repo.findById(id)
                .map(p -> new ProductCore(
                        p.getId(),
                        p.getName(),
                        p.getDescription(),
                        p.getPrice(),
                        p.getCategory(),
                        p.getOwnerId(),
                        p.getImageUrl(),
                        p.getCreatedAt()
                ));
    }

    public record ProductCore(
            Long id,
            String name,
            String description,
            BigDecimal price,
            String category,
            Long ownerId,
            String imageUrl,
            Instant createdAt
    ) {
    }
}