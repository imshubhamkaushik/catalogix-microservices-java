package com.catalogix.notification.svc;

import com.catalogix.notification.dto.NotificationResponse;
import com.catalogix.notification.dto.SendEmailRequest;
import com.catalogix.notification.model.Notification;
import com.catalogix.notification.model.NotificationStatus;
import com.catalogix.notification.repository.NotificationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends a plain-text email and records the attempt (success or failure) in
 * notification_log either way — this is an audit trail, not a retry queue.
 * A failed send here does NOT throw back to the caller as an HTTP error;
 * the response body's `status` field is FAILED, and it's up to the caller
 * (typically a best-effort, non-blocking call — see order-svc/user-svc) to
 * decide whether that matters to them.
 */
@Service
public class EmailSvc {

    private static final Logger log = LoggerFactory.getLogger(EmailSvc.class);

    private final JavaMailSender mailSender;
    private final NotificationRepository repo;
    private final String fromAddress;

    public EmailSvc(
            JavaMailSender mailSender,
            NotificationRepository repo,
            @Value("${MAIL_FROM:noreply@catalogix.local}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.repo = repo;
        this.fromAddress = fromAddress;
    }

    @Transactional
    public NotificationResponse send(SendEmailRequest req) {
        Notification entry = new Notification(req.getTo(), req.getSubject(), req.getBody());

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(req.getTo());
            message.setSubject(req.getSubject());
            message.setText(req.getBody());
            mailSender.send(message);
            entry.setStatus(NotificationStatus.SENT);
        } catch (MailException e) {
            log.warn("Failed to send email to {}: {}", req.getTo(), e.getMessage());
            entry.setStatus(NotificationStatus.FAILED);
            entry.setError(truncate(e.getMessage()));
        }

        Notification saved = repo.save(entry);
        return new NotificationResponse(saved.getId(), saved.getStatus(), saved.getError());
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
