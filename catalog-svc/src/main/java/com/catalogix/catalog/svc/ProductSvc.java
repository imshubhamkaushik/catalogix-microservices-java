package com.catalogix.catalog.svc;

import com.catalogix.catalog.client.InventoryClient;
import com.catalogix.catalog.dto.CreateProductRequest;
import com.catalogix.catalog.dto.PagedResponse;
import com.catalogix.catalog.dto.ProductResponse;
import com.catalogix.catalog.exception.ForbiddenException;
import com.catalogix.catalog.exception.ProductNotFoundException;
import com.catalogix.catalog.model.Product;
import com.catalogix.catalog.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
public class ProductSvc {

    private final ProductRepository repo;
    private final InventoryClient inventoryClient;

    public ProductSvc(ProductRepository repo, InventoryClient inventoryClient) {
        this.repo = repo;
        this.inventoryClient = inventoryClient;
    }

    @Transactional(readOnly = true)
    public PagedResponse<ProductResponse> search(String search, String category, Pageable pageable, String bearerToken) {
        String normalizedSearch = StringUtils.hasText(search) ? search.trim() : null;
        String normalizedCategory = StringUtils.hasText(category) ? category.trim() : null;
        Page<Product> page = repo.search(normalizedSearch, normalizedCategory, pageable);
        // NOTE: fetches stock per-row (N+1) rather than one batched call — a
        // real deployment at meaningful result-set size would want a batch
        // GET /inventory?productIds=... endpoint here. Left as a known
        // limitation: it's the clearest cost this split adds that a single
        // shared table never had.
        return PagedResponse.from(page,
                page.getContent().stream().map(p -> toResponse(p, bearerToken)).toList());
    }

    @Transactional
    public ProductResponse create(CreateProductRequest req, Long ownerId, String bearerToken) {
        Product p = new Product();
        p.setName(req.getName());
        p.setDescription(req.getDescription());
        p.setPrice(req.getPrice());
        p.setCategory(StringUtils.hasText(req.getCategory()) ? req.getCategory().trim() : "GENERAL");
        p.setOwnerId(ownerId);
        Product saved = repo.save(p);

        int initialStock = req.getStockQuantity() != null ? req.getStockQuantity() : 0;
        inventoryClient.init(saved.getId(), initialStock, bearerToken);

        return new ProductResponse(saved.getId(), saved.getName(), saved.getDescription(), saved.getPrice(),
                saved.getCategory(), initialStock, saved.getOwnerId(), saved.getCreatedAt());
    }

    /**
     * Cached WITHOUT stock (see cacheCore) — stock is fetched live on every
     * call and merged in afterwards. Now that inventory-svc can be written
     * to directly by checkout-svc without ever touching this service, a
     * cached stock figure here could go stale in a way nothing here would
     * ever evict — so it simply isn't cached.
     */
    @Transactional(readOnly = true)
    public Optional<ProductResponse> findById(long id, String bearerToken) {
        return cacheCore(id).map(core -> {
            Integer stock = inventoryClient.fetchQuantity(id, bearerToken);
            return new ProductResponse(core.id(), core.name(), core.description(), core.price(),
                    core.category(), stock, core.ownerId(), core.createdAt());
        });
    }

    @Cacheable(value = "products", key = "#id", unless = "#result == null || #result.isEmpty()")
    @Transactional(readOnly = true)
    public Optional<ProductCore> cacheCore(long id) {
        return repo.findById(id).map(p -> new ProductCore(
                p.getId(), p.getName(), p.getDescription(), p.getPrice(),
                p.getCategory(), p.getOwnerId(), p.getCreatedAt()));
    }

    @CacheEvict(value = "products", key = "#id")
    @Transactional
    public boolean deleteById(long id, Long requesterId, String requesterRole) {
        Optional<Product> existing = repo.findById(id);
        if (existing.isEmpty()) return false;

        Product product = existing.get();
        boolean isOwner = product.getOwnerId() != null && product.getOwnerId().equals(requesterId);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(requesterRole);
        if (!isOwner && !isAdmin) {
            throw new ForbiddenException("Only the product's owner or an admin may delete it");
        }

        repo.deleteById(id);
        return true;
    }

    @Transactional(readOnly = true)
    public ProductResponse adjustStock(long id, int delta, String bearerToken) {
        if (!repo.existsById(id)) {
            throw new ProductNotFoundException(id);
        }
        Integer newQuantity = inventoryClient.adjust(id, delta, bearerToken);
        return findById(id, bearerToken)
                .map(r -> { r.setStockQuantity(newQuantity); return r; })
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    private record ProductCore(Long id, String name, String description,
                                java.math.BigDecimal price, String category,
                                Long ownerId, java.time.Instant createdAt) {}

    private ProductResponse toResponse(Product p, String bearerToken) {
        Integer stock = inventoryClient.fetchQuantity(p.getId(), bearerToken);
        return new ProductResponse(p.getId(), p.getName(), p.getDescription(), p.getPrice(),
                p.getCategory(), stock, p.getOwnerId(), p.getCreatedAt());
    }
}
