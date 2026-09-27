package com.catalogix.search.config;

import com.catalogix.search.event.ProductEvent;
import com.catalogix.search.repository.ProductDocumentRepository;
import com.catalogix.search.service.SearchService;
import com.catalogix.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Component
public class CatalogReindexJob {
    private final SearchService search;
    private final ProductDocumentRepository repository;
    private final JwtService jwtService;
    private final String catalogUrl;
    private final boolean enabled;
    private final RestClient restClient;
    private final JsonMapper mapper = new JsonMapper();

    public CatalogReindexJob(SearchService search, ProductDocumentRepository repository, JwtService jwtService,
                             @Value("${catalogix.catalog-url}") String catalogUrl,
                             @Value("${catalogix.reindex.enabled:true}") boolean enabled) {
        this.search = search;
        this.repository = repository;
        this.jwtService = jwtService;
        this.catalogUrl = catalogUrl;
        this.enabled = enabled;
        this.restClient = RestClient.builder().baseUrl(catalogUrl).build();
    }

    @Scheduled(initialDelayString="${SEARCH_REINDEX_INITIAL_DELAY_MS:5000}", fixedDelayString="${SEARCH_REINDEX_RETRY_DELAY_MS:30000}")
    public void reindexUntilHealthy() {
        if (enabled && repository.count() == 0) reindex();
    }

    @Scheduled(initialDelayString="${SEARCH_REINDEX_INITIAL_DELAY_MS:15000}", fixedDelayString="${SEARCH_REINDEX_INTERVAL_MS:3600000}")
    public void periodicReindex() {
        if (enabled) reindex();
    }

    private void reindex() {
        Set<Long> seenIds = new HashSet<>();
        try {
            String token = jwtService.generateSystemToken();
            int page = 0;
            boolean last = false;
            boolean authoritative = false;

            while (!last) {
                String body = restClient.get()
                        .uri(uri -> uri.path("/products")
                                .queryParam("page", page)
                                .queryParam("size", 200)
                                .build())
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .body(String.class);

                JsonNode root = mapper.readTree(body);
                JsonNode content = root.path("content");
                if (!content.isArray()) return;
                authoritative = true;

                for (JsonNode product : content) {
                    long id = product.path("id").asLong(0);
                    if (id <= 0) continue;
                    seenIds.add(id);
                    search.apply(new ProductEvent(
                            id,
                            product.path("name").asText(),
                            product.path("description").isNull() ? null : product.path("description").asText(),
                            product.path("price").decimalValue(),
                            product.path("category").asText(null),
                            product.path("ownerId").isNull() ? null : product.path("ownerId").asLong(),
                            product.path("imageUrl").asText(null),
                            product.path("moderationStatus").asText("PUBLISHED"),
                            Instant.now(),
                            false));
                }

                last = root.path("last").asBoolean(true);
                page++;
                if (page > 1000) return; // hard safety cap against a malformed upstream page response
            }

            if (authoritative) {
                repository.findAll().stream()
                        .map(d -> d.getId())
                        .filter(id -> id != null && !seenIds.contains(id))
                        .forEach(repository::deleteById);
            }
        } catch (Exception ignored) {
            // Search is a derived read model; a failed reconciliation must never erase
            // the existing index. The next scheduled attempt will retry.
        }
    }
}
