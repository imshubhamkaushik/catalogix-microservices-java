package com.catalogix.product.svc;

import com.catalogix.product.dto.CreateProductRequest;
import com.catalogix.product.dto.PagedResponse;
import com.catalogix.product.dto.ProductResponse;
import com.catalogix.product.exception.ForbiddenException;
import com.catalogix.product.exception.InsufficientStockException;
import com.catalogix.product.exception.ProductNotFoundException;
import com.catalogix.product.model.Product;
import com.catalogix.product.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

/* Business Logic lives in the service; the controller only handles HTTP concerns.
   Caller identity (ownerId / role) is derived from a verified JWT by JwtAuthFilter,
   never trusted from a client-supplied header. */

@Service
public class ProductSvc {

    private final ProductRepository repo;

    public ProductSvc(ProductRepository repo) {
        this.repo = repo;
    }

    // Read-only transaction — keeps a consistent snapshot for the paged fetch.
    @Transactional(readOnly = true)
    public PagedResponse<ProductResponse> search(String search, String category, Pageable pageable) {
        String normalizedSearch = StringUtils.hasText(search) ? search.trim() : null;
        String normalizedCategory = StringUtils.hasText(category) ? category.trim() : null;
        Page<Product> page = repo.search(normalizedSearch, normalizedCategory, pageable);
        return PagedResponse.from(page, page.getContent().stream().map(this::toResponse).toList());
    }

    // @Transactional ensures the save and any constraint checks are atomic.
    @Transactional
    public ProductResponse create(CreateProductRequest req, Long ownerId) {
        Product p = new Product();
        p.setName(req.getName());
        p.setDescription(req.getDescription());
        p.setPrice(req.getPrice());
        p.setCategory(StringUtils.hasText(req.getCategory()) ? req.getCategory().trim() : "GENERAL");
        p.setStockQuantity(req.getStockQuantity() != null ? req.getStockQuantity() : 0);
        p.setOwnerId(ownerId);
        return toResponse(repo.save(p));
    }

    // Cached briefly (see CacheConfig) since order-svc calls this for every line
    // item it prices — a hot, repeated, mostly-unchanging read.
    @Cacheable(value = "products", key = "#id", unless = "#result == null || #result.isEmpty()")
    @Transactional(readOnly = true)
    public Optional<ProductResponse> findById(long id) {
        return repo.findById(id).map(this::toResponse);
    }

    // @Transactional wraps the ownership check + delete atomically.
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

    /**
     * Adjusts stock by {@code delta} (positive = restock, negative = reserve/sell).
     * Uses a row-level lock so two concurrent adjustments (e.g. two orders for the
     * last unit) can't both read the same starting quantity.
     */
    @CacheEvict(value = "products", key = "#id")
    @Transactional
    public ProductResponse adjustStock(long id, int delta) {
        Product product = repo.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        int current = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        int updated = current + delta;
        if (updated < 0) {
            throw new InsufficientStockException(id, current, -delta);
        }

        product.setStockQuantity(updated);
        return toResponse(repo.save(product));
    }

    private ProductResponse toResponse(Product p) {
        return new ProductResponse(
                p.getId(), p.getName(), p.getDescription(), p.getPrice(),
                p.getCategory(), p.getStockQuantity(), p.getOwnerId(), p.getCreatedAt());
    }
}
