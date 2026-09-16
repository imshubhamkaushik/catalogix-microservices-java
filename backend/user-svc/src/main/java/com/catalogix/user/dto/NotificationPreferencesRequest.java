package com.catalogix.user.dto;

// Both fields required (not optional/partial like UpdateProfileRequest) —
// this endpoint always represents the full current state of both toggles,
// so there's no ambiguity about what a missing field means.
public class NotificationPreferencesRequest {

    private boolean orderEmailsEnabled;
    private boolean promoEmailsEnabled;

    public NotificationPreferencesRequest() {}

    public boolean isOrderEmailsEnabled() { return orderEmailsEnabled; }
    public void setOrderEmailsEnabled(boolean orderEmailsEnabled) { this.orderEmailsEnabled = orderEmailsEnabled; }

    public boolean isPromoEmailsEnabled() { return promoEmailsEnabled; }
    public void setPromoEmailsEnabled(boolean promoEmailsEnabled) { this.promoEmailsEnabled = promoEmailsEnabled; }
}
