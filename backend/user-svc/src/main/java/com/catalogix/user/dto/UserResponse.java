package com.catalogix.user.dto;

import java.time.Instant;

// DTO returned to client (never includes password).

public class UserResponse {

    private Long id;
    private String name;
    private String email;
    private String role;
    private boolean verified;
    private Instant createdAt;
    private boolean orderEmailsEnabled;
    private boolean promoEmailsEnabled;

    public UserResponse() {}

    // Kept for existing callers that don't care about verification status
    // (defaults verified to false); prefer the fuller constructor for real use.
    public UserResponse(Long id, String name, String email, String role) {
        this(id, name, email, role, false);
    }

    // Kept for existing callers from before createdAt/notification prefs
    // existed (see V6 migration) — defaults createdAt to null and both
    // preference flags to their DB default of true.
    public UserResponse(Long id, String name, String email, String role, boolean verified) {
        this(id, name, email, role, verified, null, true, true);
    }

    public UserResponse(Long id, String name, String email, String role, boolean verified,
                         Instant createdAt, boolean orderEmailsEnabled, boolean promoEmailsEnabled) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.role = role;
        this.verified = verified;
        this.createdAt = createdAt;
        this.orderEmailsEnabled = orderEmailsEnabled;
        this.promoEmailsEnabled = promoEmailsEnabled;
    }

    public Long getId() { return id;}

    public String getName() { return name;}

    public String getEmail() { return email; }

    public String getRole() { return role; }

    public boolean isVerified() { return verified; }

    public Instant getCreatedAt() { return createdAt; }

    public boolean isOrderEmailsEnabled() { return orderEmailsEnabled; }

    public boolean isPromoEmailsEnabled() { return promoEmailsEnabled; }

    public void setId(Long id) { this.id = id; }

    public void setName(String name) { this.name = name; }

    public void setEmail(String email) { this.email = email; }

    public void setRole(String role) { this.role = role; }

    public void setVerified(boolean verified) { this.verified = verified; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public void setOrderEmailsEnabled(boolean orderEmailsEnabled) { this.orderEmailsEnabled = orderEmailsEnabled; }

    public void setPromoEmailsEnabled(boolean promoEmailsEnabled) { this.promoEmailsEnabled = promoEmailsEnabled; }
}
