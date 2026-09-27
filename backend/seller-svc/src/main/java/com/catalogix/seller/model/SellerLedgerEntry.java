package com.catalogix.seller.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "seller_ledger", indexes = @Index(name = "idx_seller_ledger_user", columnList = "seller_user_id"), uniqueConstraints = @UniqueConstraint(name = "uk_seller_ledger_source", columnNames = "source_key"))
public class SellerLedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "seller_user_id", nullable = false)
    private Long sellerUserId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LedgerType type;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @Column(name = "source_key", nullable = false, length = 160)
    private String sourceKey;
    @Column(length = 300)
    private String description;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public SellerLedgerEntry() {
    }

    public SellerLedgerEntry(Long user, LedgerType type, BigDecimal amount, String source, String desc) {
        this.sellerUserId = user;
        this.type = type;
        this.amount = amount;
        this.sourceKey = source;
        this.description = desc;
    }

    public Long getId() {
        return id;
    }

    public Long getSellerUserId() {
        return sellerUserId;
    }

    public LedgerType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
