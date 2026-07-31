package com.catalogix.notification.controller;

import com.catalogix.notification.dto.NotificationResponse;
import com.catalogix.notification.dto.SendEmailRequest;
import com.catalogix.notification.exception.ForbiddenException;
import com.catalogix.notification.svc.EmailSvc;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Internal, service-to-service API — never meant to be called by an end
// user's own session token, hence the SYSTEM role check on every endpoint.
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final EmailSvc emailSvc;

    public NotificationController(EmailSvc emailSvc) {
        this.emailSvc = emailSvc;
    }

    @PostMapping("/email")
    public ResponseEntity<NotificationResponse> sendEmail(
            @RequestAttribute("userRole") String role,
            @Valid @RequestBody SendEmailRequest req
    ) {
        requireSystemCaller(role);
        return ResponseEntity.ok(emailSvc.send(req));
    }

    private void requireSystemCaller(String role) {
        if (!"SYSTEM".equalsIgnoreCase(role)) {
            throw new ForbiddenException("notification-svc only accepts calls from trusted internal services");
        }
    }
}
