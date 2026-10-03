package com.catalogix.user.dto;

// Deliberately smaller than UserResponse — this is returned from the
// SYSTEM-only internal lookup endpoint (see UserController's
// GET /users/{id}/notification-preferences), which has no business handing
// a caller like notification-svc the requesting user's name, email, role,
// etc. just to answer "should this one email be sent".
public class NotificationPreferencesResponse {

    private boolean orderEmailsEnabled;

    public NotificationPreferencesResponse() {
    }

    public NotificationPreferencesResponse(boolean orderEmailsEnabled) {
        this.orderEmailsEnabled = orderEmailsEnabled;
    }

    public boolean isOrderEmailsEnabled() {
        return orderEmailsEnabled;
    }

    public void setOrderEmailsEnabled(boolean orderEmailsEnabled) {
        this.orderEmailsEnabled = orderEmailsEnabled;
    }

}
