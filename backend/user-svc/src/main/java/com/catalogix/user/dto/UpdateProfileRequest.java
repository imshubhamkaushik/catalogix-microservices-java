package com.catalogix.user.dto;

import jakarta.validation.constraints.Email;

// All fields optional except currentPassword (required whenever email or
// newPassword changes, as a lightweight re-auth check) — see UserSvc.updateProfile.
public class UpdateProfileRequest {

    private String name;

    @Email(message = "email must be valid")
    private String email;

    private String newPassword;

    private String currentPassword;

    public UpdateProfileRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
}
