package com.catalogix.catalog.config;

import com.catalogix.catalog.dto.CreateProductRequest;
import com.catalogix.catalog.repository.ProductRepository;
import com.catalogix.catalog.svc.ProductSvc;
import com.catalogix.security.JwtService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds a small sample catalogue on startup so a fresh environment has
 * something to look at immediately instead of an empty product grid. Same
 * SEED_DATA gate and reasoning as user-svc's AdminSeeder — off by default,
 * idempotent (skips entirely if any product already exists, not just an
 * exact-match check per product, since this is meant to run once against an
 * empty catalogue, not reconcile against hand-edited data on every restart).
 *
 * Goes through the real ProductSvc.create() (not a direct repository save)
 * specifically so inventory-svc also gets a matching stock record via
 * InventoryClient.init() — a direct repo.save() here would leave every
 * seeded product with no corresponding inventory row at all, which is worse
 * than not seeding anything.
 *
 * ownerId 0L is a sentinel for "system-seeded", not a real user-svc id —
 * there's no foreign key across service boundaries (see Product entity),
 * so this only matters for the frontend's "can this user manage this
 * product" check, which an id of 0 never matches for a real logged-in user
 * (only an admin can delete/manage these, same as any other product they
 * don't own).
 */
@Component
public class ProductSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductSeeder.class);
    private static final Long SEED_OWNER_ID = 0L;

    private final ProductRepository repo;
    private final ProductSvc productSvc;
    private final JwtService jwtService;
    private final boolean seedEnabled;

    public ProductSeeder(
            ProductRepository repo,
            ProductSvc productSvc,
            JwtService jwtService,
            @Value("${SEED_DATA:false}") boolean seedEnabled
    ) {
        this.repo = repo;
        this.productSvc = productSvc;
        this.jwtService = jwtService;
        this.seedEnabled = seedEnabled;
    }

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            return;
        }
        if (repo.count() > 0) {
            return; // already seeded (or real data exists) — never touch it
        }

        // Seed data is a nice-to-have, not a hard requirement — a failure
        // here (most likely inventory-svc not being reachable yet if it's
        // still booting when this runs; there's a depends_on in
        // docker-compose.yaml to reduce that race, but not eliminate it)
        // must never take catalog-svc itself down. CommandLineRunner
        // exceptions fail the whole Spring Boot startup by default, so this
        // catches broadly on purpose.
        try {
            String systemToken = jwtService.generateSystemToken();
            int created = 0;
            for (SeedProduct sp : SAMPLE_PRODUCTS) {
                CreateProductRequest req = new CreateProductRequest();
                req.setName(sp.name);
                req.setDescription(sp.description);
                req.setPrice(sp.price);
                req.setCategory(sp.category);
                req.setStockQuantity(sp.stock);
                req.setImageUrl(sp.imageUrl);
                productSvc.create(req, SEED_OWNER_ID, systemToken);
                created++;
            }
            log.info("Seeded {} sample products", created);
        } catch (Exception e) {
            log.warn("Product seeding failed partway through (often just inventory-svc still "
                    + "starting up) — catalog-svc is starting normally regardless: {}", e.getMessage());
        }
    }

    private record SeedProduct(String name, String description, BigDecimal price,
                                String category, int stock, String imageUrl) {}

    private static final List<SeedProduct> SAMPLE_PRODUCTS = List.of(
            new SeedProduct("Wireless Headphones", "Over-ear, active noise cancellation, 30hr battery",
                    new BigDecimal("2499.00"), "Electronics", 40, null),
            new SeedProduct("Smart Watch", "Heart-rate and sleep tracking, 7-day battery",
                    new BigDecimal("5999.00"), "Electronics", 25, null),
            new SeedProduct("Mechanical Keyboard", "Hot-swappable switches, per-key RGB",
                    new BigDecimal("3499.00"), "Electronics", 15, null),
            new SeedProduct("Steel Water Bottle", "1 litre, vacuum insulated, keeps cold 24hr",
                    new BigDecimal("799.00"), "Home", 100, null),
            new SeedProduct("Ceramic Coffee Mug Set", "Set of 4, microwave and dishwasher safe",
                    new BigDecimal("999.00"), "Home", 60, null),
            new SeedProduct("Cotton Bedsheet Set", "Queen size, 300 thread count, 2 pillow covers",
                    new BigDecimal("1899.00"), "Home", 30, null),
            new SeedProduct("Running Shoes", "Lightweight mesh upper, cushioned sole",
                    new BigDecimal("2999.00"), "Sports", 50, null),
            new SeedProduct("Yoga Mat", "6mm thick, non-slip, carry strap included",
                    new BigDecimal("899.00"), "Sports", 70, null),
            new SeedProduct("Backpack", "20L, laptop compartment, water resistant",
                    new BigDecimal("1799.00"), "Fashion", 35, null),
            new SeedProduct("Paperback Novel Bundle", "Set of 3 bestsellers",
                    new BigDecimal("649.00"), "Books", 45, null)
    );
}
