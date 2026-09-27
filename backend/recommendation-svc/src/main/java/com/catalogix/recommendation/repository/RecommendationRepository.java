package com.catalogix.recommendation.repository;

import com.catalogix.recommendation.model.RecommendationEdge;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface RecommendationRepository extends JpaRepository<RecommendationEdge, Long> {
  @Query("select r from RecommendationEdge r where r.productId=:productId order by r.score desc")
  List<RecommendationEdge> top(Long productId, Pageable pageable);

  Optional<RecommendationEdge> findByProductIdAndRelatedProductId(Long productId, Long relatedProductId);
}
