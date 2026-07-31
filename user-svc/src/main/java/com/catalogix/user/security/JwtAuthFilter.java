package com.catalogix.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates the "Authorization: Bearer <token>" header on every request
 * except the public auth endpoints and the health/metrics endpoints.
 *
 * On success, the caller's identity is attached to the request as
 * attributes ("userId", "userEmail", "userRole") for controllers to read
 * via @RequestAttribute — this replaces trusting a client-supplied header.
 */
@Component
@Order(2)
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/users/register")
                || path.equals("/users/login")
                || path.equals("/users/refresh")
                || path.equals("/users/logout")
                || path.equals("/users/verify-email")
                || path.equals("/users/forgot-password")
                || path.equals("/users/reset-password")
                || path.equals("/health")
                || path.startsWith("/actuator")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, "Missing or malformed Authorization header");
            return;
        }

        String token = header.substring(7).trim();
        try {
            Claims claims = jwtService.parseClaims(token);
            request.setAttribute("userId", Long.valueOf(claims.getSubject()));
            request.setAttribute("userEmail", claims.get("email", String.class));
            request.setAttribute("userRole", claims.get("role", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            reject(response, "Invalid or expired token");
            return;
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("status", HttpServletResponse.SC_UNAUTHORIZED);
        body.put("timestamp", Instant.now().toString());
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
