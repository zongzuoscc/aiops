package org.example.incident.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(
        name = "aiops.control-plane.security.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ControlPlaneApiKeyFilter extends OncePerRequestFilter {

    private final String apiKey;

    public ControlPlaneApiKeyFilter(
            @Value("${aiops.control-plane.security.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v2/")
                || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader("X-API-Key");
        String authorization = request.getHeader("Authorization");
        if (supplied == null && authorization != null && authorization.startsWith("Bearer ")) {
            supplied = authorization.substring(7);
        }
        boolean configured = !apiKey.isBlank();
        boolean matches = supplied != null && MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
        if (!configured || !matches) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid control-plane API key");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
