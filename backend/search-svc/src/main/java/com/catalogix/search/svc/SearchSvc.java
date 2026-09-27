package com.catalogix.search.svc;

import com.catalogix.search.dto.SearchResponse;
import com.catalogix.search.event.ProductEvent;
import com.catalogix.search.model.ProductDocument;
import com.catalogix.search.repository.ProductDocumentRepository;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Optional;

@Service
public class SearchSvc {
    private final ProductDocumentRepository repo;

    public SearchSvc(ProductDocumentRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void apply(ProductEvent e) {
        if (e.deleted()) {
            repo.deleteById(e.id());
            return;
        }

        Optional<ProductDocument> existing = repo.findById(e.id());
        ProductDocument product;

        if (existing.isPresent()) {
            product = existing.get();

            // Ignore stale events only for an already-existing document.
            if (product.getUpdatedAt() != null
                    && e.occurredAt() != null
                    && product.getUpdatedAt().isAfter(e.occurredAt())) {
                return;
            }
        } else {
            // New product event: create the document regardless of the
            // default updatedAt value on ProductDocument.
            product = new ProductDocument();
        }

        product.setId(e.id());
        product.setName(e.name());
        product.setDescription(e.description());
        product.setPrice(e.price());
        product.setCategory(e.category());
        product.setOwnerId(e.ownerId());
        product.setImageUrl(e.imageUrl());
        product.setModerationStatus(
                e.moderationStatus() == null
                        ? "PUBLISHED"
                        : e.moderationStatus());

        product.setUpdatedAt(
                e.occurredAt() == null
                        ? java.time.Instant.now()
                        : e.occurredAt());

        repo.save(product);
    }

    @Transactional(readOnly = true)
    public Page<SearchResponse> search(String q, String category, BigDecimal min, BigDecimal max, Pageable pageable) {
        return repo.search(q == null ? "" : q.trim(), category == null ? "" : category.trim(), min, max, pageable)
                .map(p -> new SearchResponse(p.getId(), p.getName(), p.getDescription(), p.getPrice(), p.getCategory(),
                        p.getOwnerId(), p.getImageUrl(), p.getUpdatedAt()));
    }
}
