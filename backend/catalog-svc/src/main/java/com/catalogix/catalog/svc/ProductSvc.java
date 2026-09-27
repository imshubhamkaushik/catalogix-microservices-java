package com.catalogix.catalog.svc;

import com.catalogix.catalog.client.InventoryClient;
import com.catalogix.catalog.client.ReviewClient;
import com.catalogix.catalog.dto.CreateProductRequest;
import com.catalogix.catalog.dto.PagedResponse;
import com.catalogix.catalog.dto.ProductResponse;
import com.catalogix.catalog.dto.ProductSortOption;
import com.catalogix.catalog.exception.ForbiddenException;
import com.catalogix.catalog.exception.ProductNotFoundException;
import com.catalogix.catalog.model.Product;
import com.catalogix.catalog.repository.ProductRepository;
import com.catalogix.catalog.model.Product.ModerationStatus;
import com.catalogix.catalog.event.ProductDomainEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class ProductSvc {

        private final ProductRepository repo;
        private final InventoryClient inventoryClient;
        private final ReviewClient reviewClient;
        private final ProductCacheSvc productCacheSvc;
        private ApplicationEventPublisher eventPublisher;

        public ProductSvc(
                        ProductRepository repo,
                        InventoryClient inventoryClient,
                        ReviewClient reviewClient,
                        ProductCacheSvc productCacheSvc) {
                this.repo = repo;
                this.inventoryClient = inventoryClient;
                this.reviewClient = reviewClient;
                this.productCacheSvc = productCacheSvc;
        }

        @Autowired
        public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
                this.eventPublisher = eventPublisher;
        }

        // Backward-compatible overload retained for existing unit tests/callers.
        @Transactional(readOnly = true)
        public PagedResponse<ProductResponse> search(
                        String search, String category, BigDecimal minPrice, BigDecimal maxPrice,
                        ProductSortOption sortBy, Pageable pageable, String bearerToken) {
                return search(search, category, minPrice, maxPrice, sortBy, pageable, bearerToken, null, "ADMIN");
        }

        @Transactional(readOnly = true)
        public PagedResponse<ProductResponse> search(
                        String search,
                        String category,
                        BigDecimal minPrice,
                        BigDecimal maxPrice,
                        ProductSortOption sortBy,
                        Pageable pageable,
                        String bearerToken,
                        Long requesterId,
                        String requesterRole) {
                String normalizedSearch = StringUtils.hasText(search) ? search.trim() : "";
                String normalizedCategory = StringUtils.hasText(category) ? category.trim() : "";

                // A caller-supplied sortBy takes over ordering entirely rather than
                // combining with whatever Pageable's own ?sort= carried — mixing
                // the two would mean the friendly enum sometimes wins and sometimes
                // doesn't depending on param order, which is more confusing than
                // just "sortBy always wins when present".
                Pageable effectivePageable = sortBy != null
                                ? PageRequest.of(
                                                pageable.getPageNumber(),
                                                pageable.getPageSize(),
                                                sortBy.toSort())
                                : pageable;

                Page<Product> page = repo.search(
                                normalizedSearch,
                                normalizedCategory,
                                minPrice,
                                maxPrice,
                                "ADMIN".equalsIgnoreCase(requesterRole),
                                requesterId,
                                effectivePageable);

                // NOTE: fetches stock per-row (N+1) rather than one batched call — a
                // real deployment at meaningful result-set size would want a batch
                // GET /inventory?productIds=... endpoint here. Left as a known
                // limitation: it's the clearest cost this split adds that a single
                // shared table never had.
                return PagedResponse.from(
                                page,
                                page.getContent()
                                                .stream()
                                                .map(p -> toResponse(p, bearerToken))
                                                .toList());
        }

        @Transactional
        public ProductResponse create(CreateProductRequest req, Long ownerId, String bearerToken) {
                return create(req, ownerId, "ADMIN", bearerToken);
        }

        @Transactional
        public ProductResponse create(
                        CreateProductRequest req,
                        Long ownerId,
                        String requesterRole,
                        String bearerToken) {
                Product p = new Product();
                p.setName(req.getName());
                p.setDescription(req.getDescription());
                p.setPrice(req.getPrice());
                p.setCategory(
                                StringUtils.hasText(req.getCategory())
                                                ? req.getCategory().trim()
                                                : "GENERAL");
                p.setOwnerId(ownerId);
                p.setImageUrl(req.getImageUrl());
                p.setModerationStatus("ADMIN".equalsIgnoreCase(requesterRole) ? Product.ModerationStatus.PUBLISHED
                                : Product.ModerationStatus.PENDING_REVIEW);

                Product saved = repo.save(p);

                int initialStock = req.getStockQuantity() != null ? req.getStockQuantity() : 0;

                inventoryClient.init(saved.getId(), initialStock, bearerToken);

                publish(new ProductDomainEvent(saved.getId(), saved.getName(), saved.getDescription(), saved.getPrice(),
                                saved.getCategory(), saved.getOwnerId(), saved.getImageUrl(),
                                saved.getModerationStatus().name(), false, java.time.Instant.now()));

                ProductResponse response = new ProductResponse(
                                saved.getId(),
                                saved.getName(),
                                saved.getDescription(),
                                saved.getPrice());

                response.setCategory(saved.getCategory());
                response.setStockQuantity(initialStock);
                response.setOwnerId(saved.getOwnerId());
                response.setImageUrl(saved.getImageUrl());
                response.setCreatedAt(saved.getCreatedAt());
                response.setModerationStatus(saved.getModerationStatus());

                return response;
        }

        /**
         * Cached WITHOUT stock (see ProductCacheSvc) — stock is fetched live on
         * every call and merged in afterwards. Now that inventory-svc can be
         * written to directly by checkout-svc without ever touching this service,
         * a cached stock figure here could go stale in a way nothing here would
         * ever evict — so it simply isn't cached.
         */
        @Transactional(readOnly = true)
        public Optional<ProductResponse> findById(
                        long id,
                        String bearerToken) {
                return productCacheSvc.cacheCore(id).map(core -> {
                        Integer stock = inventoryClient.fetchQuantity(id, bearerToken);

                        ProductResponse response = new ProductResponse(
                                        core.id(),
                                        core.name(),
                                        core.description(),
                                        core.price());

                        response.setCategory(core.category());
                        response.setStockQuantity(stock);
                        response.setOwnerId(core.ownerId());
                        response.setImageUrl(core.imageUrl());
                        response.setCreatedAt(core.createdAt());
                        response.setModerationStatus(core.moderationStatus());

                        ReviewClient.Summary rating = reviewClient.fetchSummary(id, bearerToken);

                        response.setAverageRating(rating.averageRating());
                        response.setReviewCount(rating.reviewCount());

                        return response;
                });
        }

        @CacheEvict(value = "products", key = "#id")
        @Transactional
        public boolean deleteById(
                        long id,
                        Long requesterId,
                        String requesterRole) {
                Optional<Product> existing = repo.findById(id);

                if (existing.isEmpty()) {
                        return false;
                }

                Product product = existing.get();

                boolean isOwner = product.getOwnerId() != null
                                && product.getOwnerId().equals(requesterId);

                boolean isAdmin = "ADMIN".equalsIgnoreCase(requesterRole);

                if (!isOwner && !isAdmin) {
                        throw new ForbiddenException(
                                        "Only the product's owner or an admin may delete it");
                }

                repo.deleteById(id);
                publish(new ProductDomainEvent(id, product.getName(), product.getDescription(), product.getPrice(),
                                product.getCategory(), product.getOwnerId(), product.getImageUrl(),
                                product.getModerationStatus().name(), true, java.time.Instant.now()));
                return true;
        }

        @Transactional
        public ProductResponse moderate(long id, ModerationStatus status) {
                Product product = repo.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
                product.setModerationStatus(status);
                Product saved = repo.save(product);
                publish(new ProductDomainEvent(saved.getId(), saved.getName(), saved.getDescription(), saved.getPrice(),
                                saved.getCategory(), saved.getOwnerId(), saved.getImageUrl(),
                                saved.getModerationStatus().name(), false, java.time.Instant.now()));
                ProductResponse response = new ProductResponse(saved.getId(), saved.getName(), saved.getDescription(),
                                saved.getPrice());
                response.setCategory(saved.getCategory());
                response.setOwnerId(saved.getOwnerId());
                response.setImageUrl(saved.getImageUrl());
                response.setCreatedAt(saved.getCreatedAt());
                response.setModerationStatus(saved.getModerationStatus());
                return response;
        }

        private void publish(ProductDomainEvent event) {
                if (eventPublisher != null) {
                        eventPublisher.publishEvent(event);
                }
        }

        // SECURITY FIX: this previously had no ownership/role check at all — any
        // authenticated user could adjust ANY product's stock, not just their
        // own. Now mirrors the owner-or-admin check deleteById already had.
        // The actual inventory-svc call now goes through a system-minted token
        // (see InventoryClient#adjust) rather than the caller's own bearer
        // token — inventory-svc's /adjust endpoint no longer accepts regular
        // user tokens at all, so authorization for this whole operation is
        // fully enforced right here, once, before we ever reach inventory-svc.
        @Transactional(readOnly = true)
        public ProductResponse adjustStock(
                        long id,
                        int delta,
                        Long requesterId,
                        String requesterRole,
                        String bearerToken) {
                Product product = repo.findById(id)
                                .orElseThrow(() -> new ProductNotFoundException(id));

                boolean isOwner = product.getOwnerId() != null
                                && product.getOwnerId().equals(requesterId);

                boolean isAdmin = "ADMIN".equalsIgnoreCase(requesterRole);

                if (!isOwner && !isAdmin) {
                        throw new ForbiddenException(
                                        "Only the product's owner or an admin may adjust its stock");
                }

                Integer newQuantity = inventoryClient.adjust(id, delta);

                ProductResponse response = new ProductResponse(
                                product.getId(),
                                product.getName(),
                                product.getDescription(),
                                product.getPrice());

                response.setCategory(product.getCategory());
                response.setStockQuantity(newQuantity);
                response.setOwnerId(product.getOwnerId());
                response.setImageUrl(product.getImageUrl());
                response.setCreatedAt(product.getCreatedAt());
                response.setModerationStatus(product.getModerationStatus());

                ReviewClient.Summary rating = reviewClient.fetchSummary(id, bearerToken);

                response.setAverageRating(rating.averageRating());
                response.setReviewCount(rating.reviewCount());

                return response;
        }

        private ProductResponse toResponse(
                        Product p,
                        String bearerToken) {
                Integer stock = inventoryClient.fetchQuantity(
                                p.getId(),
                                bearerToken);

                ProductResponse response = new ProductResponse(
                                p.getId(),
                                p.getName(),
                                p.getDescription(),
                                p.getPrice());

                response.setCategory(p.getCategory());
                response.setStockQuantity(stock);
                response.setOwnerId(p.getOwnerId());
                response.setImageUrl(p.getImageUrl());
                response.setCreatedAt(p.getCreatedAt());
                response.setModerationStatus(p.getModerationStatus());

                ReviewClient.Summary rating = reviewClient.fetchSummary(p.getId(), bearerToken);

                response.setAverageRating(rating.averageRating());
                response.setReviewCount(rating.reviewCount());

                return response;
        }
}