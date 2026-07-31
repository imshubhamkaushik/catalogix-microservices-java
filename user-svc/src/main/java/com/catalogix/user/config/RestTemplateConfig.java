package com.catalogix.user.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // user-svc only ever POSTs to notification-svc, so the JDK's default
        // request factory (which can't send PATCH) is fine here — unlike
        // order-svc, which needs Apache HttpClient5 for PATCH /products/{id}/stock.
        return builder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }
}
