package com.catalogix.catalog.repository;

import com.catalogix.catalog.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// No more findByIdForUpdate here — stock's row-level locking now lives in
// inventory-svc's StockItemRepository, next to the field it actually
// protects, instead of on a table that no longer has that column.
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("""
        SELECT p FROM Product p
        WHERE (:search IS NULL
               OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(p.description) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:category IS NULL OR LOWER(p.category) = LOWER(:category))
        """)
    Page<Product> search(@Param("search") String search, @Param("category") String category, Pageable pageable);
}
