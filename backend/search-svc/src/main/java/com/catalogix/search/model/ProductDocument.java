package com.catalogix.search.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "search_products")
public class ProductDocument {
  @Id
  Long id;
  @Column(nullable = false)
  String name;
  String description;
  @Column(nullable = false)
  BigDecimal price;
  @Column(nullable = false)
  String category;
  Long ownerId;
  @Column(length = 500)
  String imageUrl;
  @Column(nullable = false)
  String moderationStatus = "PUBLISHED";
  @Column(nullable = false)
  Instant updatedAt = Instant.now();

  public ProductDocument() {
    // JPA requires a no-argument constructor to instantiate entity objects.
  }

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public String getName() {
    return name;
  }

  public void setName(String v) {
    name = v;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String v) {
    description = v;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal v) {
    price = v;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String v) {
    category = v;
  }

  public Long getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(Long v) {
    ownerId = v;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public void setImageUrl(String v) {
    imageUrl = v;
  }

  public String getModerationStatus() {
    return moderationStatus;
  }

  public void setModerationStatus(String v) {
    moderationStatus = v;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant v) {
    updatedAt = v;
  }
}
