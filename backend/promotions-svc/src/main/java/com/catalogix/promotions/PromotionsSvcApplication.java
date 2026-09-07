package com.catalogix.promotions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// scanBasePackages extended beyond the default (this class's own package)
// to also pick up com.catalogix.security — the shared JwtService,
// JwtAuthFilter, RateLimiterFilter, and CorsConfig, previously hand-copied
// into every service's own package (where the default single-package scan
// would have found them with no extra config) and now consolidated into one
// module. Without this, Spring would never construct those beans here and
// every one of these 9 services would fail to start.
@SpringBootApplication(scanBasePackages = {"com.catalogix.promotions", "com.catalogix.security"})
public class PromotionsSvcApplication {
    public static void main(String[] args) {
        SpringApplication.run(PromotionsSvcApplication.class, args);
    }
}
