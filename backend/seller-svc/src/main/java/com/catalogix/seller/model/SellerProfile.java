package com.catalogix.seller.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name="seller_profiles", uniqueConstraints=@UniqueConstraint(name="uk_seller_user", columnNames="user_id"))
public class SellerProfile {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="display_name", nullable=false, length=120) private String displayName;
    @Column(name="business_name", length=160) private String businessName;
    @Column(length=500) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) private SellerStatus status=SellerStatus.PENDING;
    @Column(name="commission_rate", precision=5, scale=2, nullable=false) private BigDecimal commissionRate=new BigDecimal("8.00");
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt=Instant.now();
    @Column(name="updated_at", nullable=false) private Instant updatedAt=Instant.now();
    @PreUpdate void touch(){ updatedAt=Instant.now(); }
    public Long getId(){return id;} public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
    public String getDisplayName(){return displayName;} public void setDisplayName(String v){displayName=v;}
    public String getBusinessName(){return businessName;} public void setBusinessName(String v){businessName=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public SellerStatus getStatus(){return status;} public void setStatus(SellerStatus v){status=v;}
    public BigDecimal getCommissionRate(){return commissionRate;} public void setCommissionRate(BigDecimal v){commissionRate=v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
