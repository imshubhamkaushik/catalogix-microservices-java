package com.catalogix.notification.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class SendEmailRequest {

    @NotBlank(message = "to is required")
    @Email(message = "to must be a valid email address")
    private String to;

    @NotBlank(message = "subject is required")
    private String subject;

    @NotBlank(message = "body is required")
    private String body;

    public SendEmailRequest() {
    }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
}
