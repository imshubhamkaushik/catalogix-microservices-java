package com.catalogix.order.client;

import com.catalogix.order.security.JwtService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Calls notification-svc to send an email. Deliberately best-effort: a
 * failure here is logged and swallowed, never propagated — an order
 * confirmation email failing to send must never fail (or even slow down)
 * the order itself. Combined with OrderNotifier's @Async, this runs
 * completely off the request thread.
 */
@Component
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final RestTemplate restTemplate;
    private final JwtService jwtService;
    private final String notificationSvcUrl;

    public NotificationClient(
            RestTemplate restTemplate,
            JwtService jwtService,
            @Value("${NOTIFICATION_SVC_URL}") String notificationSvcUrl
    ) {
        this.restTemplate = restTemplate;
        this.jwtService = jwtService;
        this.notificationSvcUrl = notificationSvcUrl;
    }

    public void sendEmail(String to, String subject, String body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateSystemToken());

            Map<String, String> payload = Map.of("to", to, "subject", subject, "body", body);
            restTemplate.postForEntity(
                    notificationSvcUrl + "/notifications/email",
                    new HttpEntity<>(payload, headers),
                    Void.class);
        } catch (RestClientException e) {
            log.warn("Failed to reach notification-svc to email {} ({}): {}", to, subject, e.getMessage());
        }
    }
}
