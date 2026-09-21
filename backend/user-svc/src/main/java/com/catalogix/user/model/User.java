package com.catalogix.user.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    // "USER" (customer), "SELLER" or "ADMIN". Everyone registers as USER; only an
    // admin can assign SELLER or ADMIN (see UserSvc.assignRole). The one
    // bootstrap admin of a fresh deployment is created by AdminSeeder.
    @Column(nullable = false)
    private String role = "USER";

    // A pending request for a higher role (currently only "SELLER"), waiting for
    // an admin to approve or reject it. Both null when there is no pending request.
    @Column(name = "requested_role")
    private String requestedRole;

    @Column(name = "role_requested_at")
    private Instant roleRequestedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // Tracked for UI purposes (e.g. showing a "verify your email" banner) but
    // NOT enforced as a login gate — see V4 migration / README for why.
    @Column(nullable = false)
    private boolean verified = false;

    // Self-service opt-out toggles (see V6 migration). NOT currently
    // enforced by notification-svc — see UserSvc's notificationPreferences
    // methods for why. Stored here regardless so the preference itself is
    // real and persisted, ready for that follow-up.
    @Column(name = "order_emails_enabled", nullable = false)
    private boolean orderEmailsEnabled = true;

    @Column(name = "promo_emails_enabled", nullable = false)
    private boolean promoEmailsEnabled = true;

    public User() {
    }

    public User(String name, String email, String password) {
        this.name = name;
        this.email = email;
        this.password = password;
    }

    // getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getRequestedRole() {
        return requestedRole;
    }

    public void setRequestedRole(String requestedRole) {
        this.requestedRole = requestedRole;
    }

    public Instant getRoleRequestedAt() {
        return roleRequestedAt;
    }

    public void setRoleRequestedAt(Instant roleRequestedAt) {
        this.roleRequestedAt = roleRequestedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public boolean isOrderEmailsEnabled() {
        return orderEmailsEnabled;
    }

    public void setOrderEmailsEnabled(boolean orderEmailsEnabled) {
        this.orderEmailsEnabled = orderEmailsEnabled;
    }

    public boolean isPromoEmailsEnabled() {
        return promoEmailsEnabled;
    }

    public void setPromoEmailsEnabled(boolean promoEmailsEnabled) {
        this.promoEmailsEnabled = promoEmailsEnabled;
    }
}
