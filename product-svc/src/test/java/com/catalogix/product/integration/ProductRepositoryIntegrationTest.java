package com.catalogix.product.integration;

import com.catalogix.product.model.Product;
import com.catalogix.product.repository.ProductRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ProductRepositoryIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("JWT_SECRET", () -> "test-secret-key-at-least-32-characters-long!!");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // This test exercises the repository/schema directly; Flyway migrations are
        // covered indirectly whenever the app boots against a real Postgres instance.
        registry.add("spring.flyway.enabled", () -> false);
    }

    @Autowired
    private ProductRepository productRepository;

    @Test
    void contextLoads() {
        assertThat(productRepository).isNotNull();
    }

    // FIX: original only checked count >= 0 — a test that can never fail
    // is not a test. These now actually save, find, and delete data.

    @Test
    void saveAndFindProduct() {
        Product p = new Product("Laptop", "A fast laptop", new BigDecimal("55000.00"));
        p.setOwnerId(1L);
        Product saved = productRepository.save(p);

        Long savedId = Objects.requireNonNull(saved.getId(), "saved ID must not be null");
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Laptop");
        assertThat(saved.getPrice()).isEqualByComparingTo(new BigDecimal("55000.00"));

        Optional<Product> found = productRepository.findById(savedId);
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Laptop");
    }

    @Test
    void listAllProducts() {
        productRepository.deleteAll();
        Product phone = new Product("Phone", "A phone", new BigDecimal("15000.00"));
        phone.setOwnerId(1L);
        Product tablet = new Product("Tablet", "A tablet", new BigDecimal("25000.00"));
        tablet.setOwnerId(1L);
        productRepository.save(phone);
        productRepository.save(tablet);

        List<Product> products = productRepository.findAll();
        assertThat(products).hasSize(2);
    }

    @Test
    void deleteProduct() {
        Product p = new Product("Headphones", "Wireless", new BigDecimal("3000.00"));
        p.setOwnerId(1L);
        Product saved = productRepository.save(p);
        Long id = Objects.requireNonNull(saved.getId(), "Saved product ID should not be null");
        productRepository.deleteById(id);
        assertThat(productRepository.findById(id)).isEmpty();
    }
}