package com.catalogix.notification.controller;

import com.catalogix.notification.dto.NotificationResponse;
import com.catalogix.notification.dto.SendEmailRequest;
import com.catalogix.notification.model.NotificationStatus;
import com.catalogix.notification.security.JwtAuthFilter;
import com.catalogix.notification.security.RateLimiterFilter;
import com.catalogix.notification.svc.EmailSvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Objects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Auth is exercised via requestAttr(...) (simulating what JwtAuthFilter would set) rather
// than via a real token, so JwtAuthFilter/RateLimiterFilter are excluded from this slice —
// they'd otherwise need a real JwtService bean (JWT_SECRET etc.) just to construct.
@WebMvcTest(
        controllers = NotificationController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, RateLimiterFilter.class}))
class NotificationControllerTest {

    private static final String EMAIL_ENDPOINT = "/notifications/email";

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private EmailSvc emailSvc;

    @Autowired
    private MockMvc mvc;

    private SendEmailRequest sampleRequest() {
        SendEmailRequest req = new SendEmailRequest();
        req.setTo("alice@example.com");
        req.setSubject("Welcome");
        req.setBody("Hello Alice!");
        return req;
    }

    @Test
    @SuppressWarnings("null")
    void sendEmailReturnsOkForSystemCaller() throws Exception {
        when(emailSvc.send(any(SendEmailRequest.class)))
                .thenReturn(new NotificationResponse(1L, NotificationStatus.SENT, null));

        mvc.perform(post(EMAIL_ENDPOINT)
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    @SuppressWarnings("null")
    void sendEmailReportsFailedStatusWithoutErroringHttpWise() throws Exception {
        when(emailSvc.send(any(SendEmailRequest.class)))
                .thenReturn(new NotificationResponse(1L, NotificationStatus.FAILED, "Connection refused"));

        mvc.perform(post(EMAIL_ENDPOINT)
                .requestAttr("userRole", "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value("Connection refused"));
    }

    @Test
    void sendEmailRejectsNonSystemCaller() throws Exception {
        mvc.perform(post(EMAIL_ENDPOINT)
                .requestAttr("userRole", "USER")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                .content(Objects.requireNonNull(mapper.writeValueAsString(sampleRequest()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void sendEmailValidatesRequestBody() throws Exception {
        SendEmailRequest invalid = new SendEmailRequest();
        invalid.setTo("not-an-email");
        invalid.setSubject("");
        invalid.setBody("");

        mvc.perform(post(EMAIL_ENDPOINT)
                .requestAttr("userRole", "SYSTEM")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                .content(Objects.requireNonNull(mapper.writeValueAsString(invalid))))
                .andExpect(status().isBadRequest());
    }
}
