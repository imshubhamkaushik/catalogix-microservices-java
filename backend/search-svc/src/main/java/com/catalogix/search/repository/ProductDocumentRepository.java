package com.catalogix.search.repository;

import com.catalogix.search.model.ProductDocument;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;

public interface ProductDocumentRepository extends JpaRepository<ProductDocument, Long> {
  @Query("select p from ProductDocument p where p.moderationStatus='PUBLISHED' and (:q='' or lower(p.name) like lower(concat('%',:q,'%')) or lower(coalesce(p.description,'')) like lower(concat('%',:q,'%'))) and (:category='' or lower(p.category)=lower(:category)) and (:min is null or p.price>=:min) and (:max is null or p.price<=:max)")
  Page<ProductDocument> search(@Param("q") String q, @Param("category") String category, @Param("min") BigDecimal min,
      @Param("max") BigDecimal max, Pageable pageable);
}
