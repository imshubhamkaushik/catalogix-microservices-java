package com.catalogix.security;

import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.beans.factory.annotation.Value;

/**
 * Was previously 9 near-identical hand-copied files (one per service
 * package); consolidated here since every copy read the same ALLOWED_ORIGINS
 * property and did the same thing with it. The one genuine difference found
 * across the 9 — user-svc needs allowCredentials(true) so the httpOnly
 * refresh-token cookie can travel on direct-to-service (non-gateway) dev
 * calls, every other service correctly has no cookie to carry and stays
 * false — is handled with a property rather than forcing a 9th copy to exist
 * for one boolean. Set CORS_ALLOW_CREDENTIALS=true only on user-svc.
 *
 * NOTE: 8 of the previous 9 copies allowed PATCH; user-svc's did not, with no
 * comment explaining why — that reads as an oversight rather than a decision,
 * so PATCH is included here for everyone. If user-svc has no PATCH endpoint
 * this is a no-op in practice; if it does, this was actually a latent gap.
 */
@Configuration
public class CorsConfig {

    @Value("${ALLOWED_ORIGINS}")
    private String allowedOrigins;

    @Value("${CORS_ALLOW_CREDENTIALS:false}")
    private boolean allowCredentials;

    @Bean
    public WebMvcConfigurer corsConfigurer() {

        String[] origins = Arrays.stream(allowedOrigins.split(","))
                  .map(s -> s != null ? s.trim() : "")
                  .toArray(String[]::new);

        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NonNull CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns(origins)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(allowCredentials);
            }
        };
    }
}
