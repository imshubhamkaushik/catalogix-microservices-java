package com.catalogix.product.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Backs the "products" cache (see ProductSvc.findById/@Cacheable) with an
 * in-memory Caffeine cache. This is per-instance, not shared across replicas
 * — fine for a single instance; scaling to multiple replicas would want
 * Redis (or similar) instead so a write on one instance invalidates the
 * others' copies too.
 *
 * Short TTL (30s) rather than "forever" specifically because order-svc reads
 * a product's price through this same endpoint when pricing an order — we'd
 * rather serve a very-slightly-stale price than one from an hour ago.
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("products");
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterWrite(30, TimeUnit.SECONDS));
        return manager;
    }
}
