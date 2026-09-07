package com.catalogix.user.dto;

// DTO returned to client (never includes password).

public class UserResponse {

    private Long id;
    private String name;
    private String email;
    private String role;
    private boolean verified;

    public UserResponse() {}

    // Kept for existing callers that don't care about verification status
    // (defaults verified to false); prefer the 5-arg constructor for real use.
    public UserResponse(Long id, String name, String email, String role) {
        this(id, name, email, role, false);
    }

    public UserResponse(Long id, String name, String email, String role, boolean verified) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.role = role;
        this.verified = verified;
    }

    public Long getId() { return id;}

    public String getName() { return name;}

    public String getEmail() { return email; }

    public String getRole() { return role; }

    public boolean isVerified() { return verified; }

    public void setId(Long id) { this.id = id; }

    public void setName(String name) { this.name = name; }

    public void setEmail(String email) { this.email = email; }

    public void setRole(String role) { this.role = role; }

    public void setVerified(boolean verified) { this.verified = verified; }
}
