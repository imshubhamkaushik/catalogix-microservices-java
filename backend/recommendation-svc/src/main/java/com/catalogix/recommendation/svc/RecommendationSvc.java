package com.catalogix.recommendation.svc;

import com.catalogix.recommendation.event.OrderConfirmedEvent;
import com.catalogix.recommendation.model.RecommendationEdge;
import com.catalogix.recommendation.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class RecommendationSvc {
    private final RecommendationRepository repo;
    private final ProcessedRecommendationEventRepository processed;

    public RecommendationSvc(RecommendationRepository r, ProcessedRecommendationEventRepository p){repo=r;processed=p;}

    @Transactional
    public void ingest(OrderConfirmedEvent e) {
        if (e == null || e.orderId() == null)
            return;
        if (processed.insertIfAbsent("order.confirmed:" + e.orderId()) == 0)
            return;
        var ids = e.items() == null ? List.<Long>of()
                : e.items().stream().map(x -> x.productId()).filter(Objects::nonNull).distinct().toList();
        for (Long a : ids)
            for (Long b : ids)
                if (!a.equals(b)) {
                    var edge = repo.findByProductIdAndRelatedProductId(a, b)
                            .orElseGet(() -> new RecommendationEdge(a, b));
                    edge.bump();
                    repo.save(edge);
                }
    }

    @Transactional(readOnly = true)
    public List<Long> recommend(Long id, int limit) {
        return repo.top(id, PageRequest.of(0, Math.min(Math.max(limit, 1), 20))).stream()
                .map(RecommendationEdge::getRelatedProductId).toList();
    }
}
