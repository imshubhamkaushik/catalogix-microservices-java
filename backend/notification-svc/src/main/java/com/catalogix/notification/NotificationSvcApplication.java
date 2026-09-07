package com.catalogix.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// scanBasePackages extended beyond the default (this class's own package)
// to also pick up com.catalogix.security — the shared JwtService,
// JwtAuthFilter, RateLimiterFilter, and CorsConfig, previously hand-copied
// into every service's own package (where the default single-package scan
// would have found them with no extra config) and now consolidated into one
// module. Without this, Spring would never construct those beans here and
// every one of these 9 services would fail to start.
@SpringBootApplication(scanBasePackages = {"com.catalogix.notification", "com.catalogix.security"})
public class NotificationSvcApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationSvcApplication.class, args);
    }
}
