package com.catalogix.notification.model;

import jakarta.persistence.*;
import java.time.Instant;

// An audit record of every notification attempt — sent or failed. Not itself
// a retry queue (see order-svc's stock_adjustment_outbox for that pattern);
// this is purely "what did we try to send, and did it work."
@Entity
@Table(name = "notification_log")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Lob
    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(length = 500)
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Notification() {
    }

    public Notification(String recipient, String subject, String body) {
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
    }

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

    public String getRecipient() {
        return recipient;
    }
    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getSubject() {
        return subject;
    }
    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }
    public void setBody(String body) {
        this.body = body;
    }

    public NotificationStatus getStatus() {
        return status;
    }
    public void setStatus(NotificationStatus status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }
    public void setError(String error) {
        this.error = error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
