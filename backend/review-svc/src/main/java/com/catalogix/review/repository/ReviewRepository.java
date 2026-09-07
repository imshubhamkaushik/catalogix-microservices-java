package com.catalogix.review.repository;

import com.catalogix.review.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByProductIdOrderByCreatedAtDesc(Long productId, Pageable pageable);

    List<Review> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Review> findByUserIdAndProductId(Long userId, Long productId);

    // Backs GET /reviews/product/{id}/summary — the endpoint catalog-svc's
    // ReviewClient calls to show a star rating on the product listing
    // without every browsing request pulling every review's full text.
    @Query("SELECT AVG(r.rating), COUNT(r) FROM Review r WHERE r.productId = :productId")
    Object[] aggregateForProduct(@Param("productId") Long productId);
}
