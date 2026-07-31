package com.catalogix.product.repository;

import com.catalogix.product.model.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // Free-text search across name/description, optionally narrowed by category.
    // Passing null for a parameter turns that filter off (see the :param IS NULL branches).
    @Query("""
        SELECT p FROM Product p
        WHERE (:search IS NULL
               OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(p.description) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:category IS NULL OR LOWER(p.category) = LOWER(:category))
        """)
    Page<Product> search(@Param("search") String search, @Param("category") String category, Pageable pageable);

    // Row-locked read used when adjusting stock, so two concurrent orders for
    // the same product can't both read a stale quantity and both succeed
    // when only one of them should.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);
}
