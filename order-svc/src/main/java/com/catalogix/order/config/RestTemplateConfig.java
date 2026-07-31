package com.catalogix.order.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // Explicitly use Apache HttpClient5 (via HttpComponentsClientHttpRequestFactory):
        // the JDK's default HttpURLConnection-based factory does not support PATCH,
        // which order-svc needs for PATCH /products/{id}/stock.
        return builder
                .requestFactory(HttpComponentsClientHttpRequestFactory.class)
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }
}
