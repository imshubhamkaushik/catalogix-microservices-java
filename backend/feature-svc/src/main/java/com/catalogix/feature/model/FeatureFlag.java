package com.catalogix.feature.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "feature_flags", uniqueConstraints = @UniqueConstraint(name = "uk_feature_name", columnNames = "name"))
public class FeatureFlag {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  @Column(nullable = false)
  String name;
  @Column(nullable = false)
  boolean enabled;
  @Column(nullable = false)
  Instant updatedAt = Instant.now();

  public FeatureFlag() {
  }

  public FeatureFlag(String n, boolean e) {
    name = n;
    enabled = e;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
    updatedAt = Instant.now();
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
