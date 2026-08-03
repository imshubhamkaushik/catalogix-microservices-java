package com.catalogix.user.event;

import java.time.Instant;

/**
 * Published (via ApplicationEventPublisher, then relayed to RabbitMQ only
 * after the DB transaction commits — see UserEventPublisher) whenever a
 * verification link needs to go out: at registration, after changing your
 * email in Account settings, or via a manual "resend" request. notification-svc
 * consumes the routed RabbitMQ message and builds the actual email; this class
 * carries only the data needed to do that.
 */
public record EmailVerificationRequestedEvent(
        String userEmail, String userName, String verificationLink, Instant occurredAt
) {
    public EmailVerificationRequestedEvent(String userEmail, String userName, String verificationLink) {
        this(userEmail, userName, verificationLink, Instant.now());
    }
}
