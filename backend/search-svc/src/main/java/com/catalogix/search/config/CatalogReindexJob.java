package com.catalogix.search.config;

import com.catalogix.search.event.ProductEvent;
import com.catalogix.search.model.ProductDocument;
import com.catalogix.search.repository.ProductDocumentRepository;
import com.catalogix.search.svc.SearchSvc;
import com.catalogix.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CatalogReindexJob.class);

    private final SearchSvc search;
    private final ProductDocumentRepository repository;
    private final JwtService jwtService;
    private final boolean enabled;
    private final RestClient restClient;
    private final JsonMapper mapper = new JsonMapper();

    public CatalogReindexJob(
            SearchSvc search,
            ProductDocumentRepository repository,
            JwtService jwtService,
            @Value("${catalogix.catalog-url}") String catalogUrl,
            @Value("${catalogix.reindex.enabled:true}") boolean enabled) {

        this.search = search;
        this.repository = repository;
        this.jwtService = jwtService;
        this.enabled = enabled;
        this.restClient = RestClient.builder()
                .baseUrl(catalogUrl)
                .build();
    }

    @Scheduled(initialDelayString = "${SEARCH_REINDEX_INITIAL_DELAY_MS:5000}", fixedDelayString = "${SEARCH_REINDEX_RETRY_DELAY_MS:30000}")
    public void reindexUntilHealthy() {
        if (enabled && repository.count() == 0) {
            reindex();
        }
    }

    @Scheduled(initialDelayString = "${SEARCH_REINDEX_INITIAL_DELAY_MS:15000}", fixedDelayString = "${SEARCH_REINDEX_INTERVAL_MS:3600000}")
    public void periodicReindex() {
        if (enabled) {
            reindex();
        }
    }

    private void reindex() {
        Set<Long> seenIds = new HashSet<>();

        try {
            String token = jwtService.generateSystemToken();

            if (processPages(token, seenIds)) {
                deleteStaleDocuments(seenIds);
            }
        } catch (RuntimeException ex) {
            // Search is a derived read model; a failed reconciliation must never
            // erase the existing index. The next scheduled attempt will retry.
            log.warn("Catalog reindex attempt failed: {}", ex.getMessage(), ex);
        }
    }

    private boolean processPages(String token, Set<Long> seenIds) {
        int page = 0;

        while (true) {
            final int currentPage = page;

            String body = restClient.get()
                    .uri(uri -> uri.path("/products")
                            .queryParam("page", currentPage)
                            .queryParam("size", 200)
                            .build())
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(body);
            JsonNode content = root.path("content");

            if (!content.isArray()) {
                return false;
            }

            collectProducts(content, seenIds);

            if (root.path("last").asBoolean(true)) {
                return true;
            }

            page++;

            if (page > 1000) {
                return false;
            }
        }
    }

    private void collectProducts(JsonNode content, Set<Long> seenIds) {
        for (JsonNode product : content) {
            long id = product.path("id").asLong(0);

            if (id <= 0) {
                continue;
            }

            seenIds.add(id);
            search.apply(toProductEvent(product, id));
        }
    }

    private ProductEvent toProductEvent(JsonNode product, long id) {
        return new ProductEvent(
                id,
                product.path("name").asString(),
                product.path("description").isNull()
                        ? null
                        : product.path("description").asString(),
                product.path("price").decimalValue(),
                product.path("category").asString(null),
                product.path("ownerId").isNull()
                        ? null
                        : product.path("ownerId").asLong(),
                product.path("imageUrl").asString(null),
                product.path("moderationStatus").asString("PUBLISHED"),
                Instant.now(),
                false);
    }

    private void deleteStaleDocuments(Set<Long> seenIds) {
        repository.findAll().stream()
                .map(ProductDocument::getId)
                .filter(id -> id != null && !seenIds.contains(id))
                .forEach(repository::deleteById);
    }
}