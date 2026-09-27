package com.catalogix.catalog.svc;

import com.catalogix.catalog.client.InventoryClient;
import com.catalogix.catalog.client.ReviewClient;
import com.catalogix.catalog.dto.CreateProductRequest;
import com.catalogix.catalog.dto.PagedResponse;
import com.catalogix.catalog.dto.ProductResponse;
import com.catalogix.catalog.dto.ProductSortOption;
import com.catalogix.catalog.event.ProductDomainEvent;
import com.catalogix.catalog.exception.ForbiddenException;
import com.catalogix.catalog.exception.ProductNotFoundException;
import com.catalogix.catalog.model.Product;
import com.catalogix.catalog.model.Product.ModerationStatus;
import com.catalogix.catalog.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
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

        private static final String ADMIN_ROLE = "ADMIN";

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

        /**
         * Requester context used by operations that need authorization information.
         *
         * Keeping these values together avoids large method signatures while
         * preserving the existing service behavior.
         */
        public record RequesterContext(
                        String bearerToken,
                        Long requesterId,
                        String requesterRole) {
        }

        /**
         * Backward-compatible search overload.
         *
         * Existing callers that do not provide requester information continue
         * to behave as before and are treated as ADMIN callers.
         */
        @Transactional(readOnly = true)
        public PagedResponse<ProductResponse> search(
                        String search,
                        String category,
                        BigDecimal minPrice,
                        BigDecimal maxPrice,
                        ProductSortOption sortBy,
                        Pageable pageable,
                        String bearerToken) {

                return doSearch(
                                search,
                                category,
                                minPrice,
                                maxPrice,
                                sortBy,
                                pageable,
                                new RequesterContext(
                                                bearerToken,
                                                null,
                                                ADMIN_ROLE));
        }

        /**
         * Search with requester context.
         */
        @Transactional(readOnly = true)
        public PagedResponse<ProductResponse> search(
                        String search,
                        String category,
                        BigDecimal minPrice,
                        BigDecimal maxPrice,
                        ProductSortOption sortBy,
                        Pageable pageable,
                        RequesterContext requester) {

                return doSearch(
                                search,
                                category,
                                minPrice,
                                maxPrice,
                                sortBy,
                                pageable,
                                requester);
        }

        /**
         * Actual search implementation.
         *
         * This is intentionally private so transactional public methods do not
         * invoke another transactional method through "this".
         */
        private PagedResponse<ProductResponse> doSearch(
                        String search,
                        String category,
                        BigDecimal minPrice,
                        BigDecimal maxPrice,
                        ProductSortOption sortBy,
                        Pageable pageable,
                        RequesterContext requester) {

                String normalizedSearch = StringUtils.hasText(search)
                                ? search.trim()
                                : "";

                String normalizedCategory = StringUtils.hasText(category)
                                ? category.trim()
                                : "";

                /*
                 * A caller-supplied sortBy takes over ordering entirely rather
                 * than combining with whatever Pageable's own ?sort= carried.
                 * Mixing the two can produce surprising ordering depending on
                 * parameter order, so sortBy wins whenever it is supplied.
                 */
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
                                ADMIN_ROLE.equalsIgnoreCase(requester.requesterRole()),
                                requester.requesterId(),
                                effectivePageable);

                /*
                 * Known limitation:
                 * stock and rating are fetched per product. A production-scale
                 * implementation would ideally batch inventory and review lookups.
                 */
                return PagedResponse.from(
                                page,
                                page.getContent()
                                                .stream()
                                                .map(product -> toResponse(
                                                                product,
                                                                requester.bearerToken()))
                                                .toList());
        }

        /**
         * Backward-compatible create overload.
         */
        @Transactional
        public ProductResponse create(
                        CreateProductRequest req,
                        Long ownerId) {

                return doCreate(
                                req,
                                ownerId,
                                ADMIN_ROLE);
        }

        /**
         * Create with requester role.
         */
        @Transactional
        public ProductResponse create(
                        CreateProductRequest req,
                        Long ownerId,
                        String requesterRole) {

                return doCreate(
                                req,
                                ownerId,
                                requesterRole);
        }

        /**
         * Actual create implementation.
         *
         * Kept private so the public transactional entrypoints do not
         * self-invoke another transactional method.
         */
        private ProductResponse doCreate(
                        CreateProductRequest req,
                        Long ownerId,
                        String requesterRole) {

                Product product = new Product();

                product.setName(req.getName());
                product.setDescription(req.getDescription());
                product.setPrice(req.getPrice());

                product.setCategory(
                                StringUtils.hasText(req.getCategory())
                                                ? req.getCategory().trim()
                                                : "GENERAL");

                product.setOwnerId(ownerId);
                product.setImageUrl(req.getImageUrl());

                product.setModerationStatus(
                                ADMIN_ROLE.equalsIgnoreCase(requesterRole)
                                                ? ModerationStatus.PUBLISHED
                                                : ModerationStatus.PENDING_REVIEW);

                Product saved = repo.save(product);

                int initialStock = req.getStockQuantity() != null
                                ? req.getStockQuantity()
                                : 0;

                inventoryClient.init(
                                saved.getId(),
                                initialStock);

                publish(
                                new ProductDomainEvent(
                                                saved.getId(),
                                                saved.getName(),
                                                saved.getDescription(),
                                                saved.getPrice(),
                                                saved.getCategory(),
                                                saved.getOwnerId(),
                                                saved.getImageUrl(),
                                                saved.getModerationStatus().name(),
                                                false,
                                                java.time.Instant.now()));

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
         * Cached WITHOUT stock.
         *
         * Inventory is intentionally fetched live because inventory-svc can be
         * updated independently by checkout-svc.
         */
        @Transactional(readOnly = true)
        public Optional<ProductResponse> findById(
                        long id,
                        String bearerToken) {

                return productCacheSvc.cacheCore(id).map(core -> {

                        Integer stock = inventoryClient.fetchQuantity(
                                        id,
                                        bearerToken);

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

                        ReviewClient.Summary rating = reviewClient.fetchSummary(
                                        id,
                                        bearerToken);

                        response.setAverageRating(
                                        rating.averageRating());

                        response.setReviewCount(
                                        rating.reviewCount());

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

                boolean isAdmin = ADMIN_ROLE.equalsIgnoreCase(requesterRole);

                if (!isOwner && !isAdmin) {
                        throw new ForbiddenException(
                                        "Only the product's owner or an admin may delete it");
                }

                repo.deleteById(id);

                publish(
                                new ProductDomainEvent(
                                                id,
                                                product.getName(),
                                                product.getDescription(),
                                                product.getPrice(),
                                                product.getCategory(),
                                                product.getOwnerId(),
                                                product.getImageUrl(),
                                                product.getModerationStatus().name(),
                                                true,
                                                java.time.Instant.now()));

                return true;
        }

        @Transactional
        public ProductResponse moderate(
                        long id,
                        ModerationStatus status) {

                Product product = repo.findById(id)
                                .orElseThrow(() -> new ProductNotFoundException(id));

                product.setModerationStatus(status);

                Product saved = repo.save(product);

                publish(
                                new ProductDomainEvent(
                                                saved.getId(),
                                                saved.getName(),
                                                saved.getDescription(),
                                                saved.getPrice(),
                                                saved.getCategory(),
                                                saved.getOwnerId(),
                                                saved.getImageUrl(),
                                                saved.getModerationStatus().name(),
                                                false,
                                                java.time.Instant.now()));

                ProductResponse response = new ProductResponse(
                                saved.getId(),
                                saved.getName(),
                                saved.getDescription(),
                                saved.getPrice());

                response.setCategory(saved.getCategory());
                response.setOwnerId(saved.getOwnerId());
                response.setImageUrl(saved.getImageUrl());
                response.setCreatedAt(saved.getCreatedAt());
                response.setModerationStatus(saved.getModerationStatus());

                return response;
        }

        /**
         * Updates inventory after verifying that the caller owns the product
         * or is an administrator.
         *
         * inventory-svc receives a system-minted authorization token for the
         * actual inventory mutation.
         */
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

                boolean isAdmin = ADMIN_ROLE.equalsIgnoreCase(requesterRole);

                if (!isOwner && !isAdmin) {
                        throw new ForbiddenException(
                                        "Only the product's owner or an admin may adjust its stock");
                }

                Integer newQuantity = inventoryClient.adjust(
                                id,
                                delta);

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

                ReviewClient.Summary rating = reviewClient.fetchSummary(
                                id,
                                bearerToken);

                response.setAverageRating(
                                rating.averageRating());

                response.setReviewCount(
                                rating.reviewCount());

                return response;
        }

        private ProductResponse toResponse(
                        Product product,
                        String bearerToken) {

                Integer stock = inventoryClient.fetchQuantity(
                                product.getId(),
                                bearerToken);

                ProductResponse response = new ProductResponse(
                                product.getId(),
                                product.getName(),
                                product.getDescription(),
                                product.getPrice());

                response.setCategory(product.getCategory());
                response.setStockQuantity(stock);
                response.setOwnerId(product.getOwnerId());
                response.setImageUrl(product.getImageUrl());
                response.setCreatedAt(product.getCreatedAt());
                response.setModerationStatus(product.getModerationStatus());

                ReviewClient.Summary rating = reviewClient.fetchSummary(
                                product.getId(),
                                bearerToken);

                response.setAverageRating(
                                rating.averageRating());

                response.setReviewCount(
                                rating.reviewCount());

                return response;
        }

        private void publish(ProductDomainEvent event) {
                if (eventPublisher != null) {
                        eventPublisher.publishEvent(event);
                }
        }
}