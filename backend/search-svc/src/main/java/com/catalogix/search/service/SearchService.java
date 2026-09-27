package com.catalogix.search.service;

import com.catalogix.search.dto.SearchResponse;
import com.catalogix.search.event.ProductEvent;
import com.catalogix.search.model.ProductDocument;
import com.catalogix.search.repository.ProductDocumentRepository;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@Service
public class SearchService {
    private final ProductDocumentRepository repo;
    public SearchService(ProductDocumentRepository repo){this.repo=repo;}

    @Transactional
    public void apply(ProductEvent e){
        if (e.deleted()) {
            repo.deleteById(e.id());
            return;
        }
        ProductDocument p=repo.findById(e.id()).orElseGet(ProductDocument::new);
        if (p.getUpdatedAt() != null && e.occurredAt() != null && p.getUpdatedAt().isAfter(e.occurredAt())) return;
        p.setId(e.id()); p.setName(e.name()); p.setDescription(e.description()); p.setPrice(e.price());
        p.setCategory(e.category()); p.setOwnerId(e.ownerId()); p.setImageUrl(e.imageUrl());
        p.setModerationStatus(e.moderationStatus()==null?"PUBLISHED":e.moderationStatus());
        p.setUpdatedAt(e.occurredAt()==null?java.time.Instant.now():e.occurredAt());
        repo.save(p);
    }

    @Transactional(readOnly=true)
    public Page<SearchResponse> search(String q,String category,BigDecimal min,BigDecimal max,Pageable pageable){
        return repo.search(q==null?"":q.trim(),category==null?"":category.trim(),min,max,pageable)
            .map(p->new SearchResponse(p.getId(),p.getName(),p.getDescription(),p.getPrice(),p.getCategory(),p.getOwnerId(),p.getImageUrl(),p.getUpdatedAt()));
    }
}
