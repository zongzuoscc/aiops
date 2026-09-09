package org.example.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.config.ApiSecurityProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ApiSecurityInterceptor implements HandlerInterceptor {

    private static final long RATE_LIMIT_WINDOW_MS = 60_000L;
    private static final int MAX_HEADER_VALUE_LENGTH = 128;
    private static final int RATE_LIMIT_EXCEEDED = 429;
    private static final int FORBIDDEN = 403;

    private final ApiSecurityProperties properties;
    private final ConcurrentHashMap<String, RateBucket> rateBuckets = new ConcurrentHashMap<>();

    public ApiSecurityInterceptor(ApiSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!properties.isEnabled()) {
            AuthContext.setCurrentOwnerId("public");
            return true;
        }

        String ownerId = resolveOwnerId(request);
        if (ownerId == null) {
            return unauthorized(response);
        }

        AuthContext.setCurrentOwnerId(ownerId);

        if (!checkRateLimit(ownerId, request, response)) {
            AuthContext.clear();
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private String resolveOwnerId(HttpServletRequest request) {
        String apiKey = request.getHeader(properties.getHeaderName());
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        apiKey = apiKey.trim();
        if (apiKey.length() > MAX_HEADER_VALUE_LENGTH) {
            return null;
        }

        Set<String> validKeys = properties.getNormalizedKeys();
        if (validKeys.isEmpty() || !validKeys.contains(apiKey)) {
            return null;
        }

        return deriveOwnerId(apiKey);
    }

    private String deriveOwnerId(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(apiKey.hashCode());
        }
    }

    private boolean checkRateLimit(String ownerId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!properties.getRateLimit().isEnabled()) {
            return true;
        }
        int limit = properties.getRateLimit().getRequestsPerMinute();
        if (limit <= 0) {
            return true;
        }

        String clientIp = resolveClientIp(request);
        String bucketKey = ownerId + "|" + clientIp;
        long now = System.currentTimeMillis();
        long windowStart = now - now % RATE_LIMIT_WINDOW_MS;

        RateBucket bucket = rateBuckets.computeIfAbsent(bucketKey, key -> new RateBucket(windowStart));
        synchronized (bucket) {
            if (bucket.windowStartMs != windowStart) {
                bucket.windowStartMs = windowStart;
                bucket.requestCount = 0;
                bucket.exceedAt = 0L;
            }
            if (bucket.requestCount >= limit) {
                if (bucket.exceedAt == 0L) {
                    bucket.exceedAt = now;
                }
                long waitUntil = bucket.windowStartMs + RATE_LIMIT_WINDOW_MS;
                String requestId = responseRequestId();
                response.setStatus(RATE_LIMIT_EXCEEDED);
                response.setHeader("X-Request-Id", requestId);
                response.setHeader("Retry-After", String.valueOf(Math.max(1L, (waitUntil - now + 999) / 1000)));
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(buildErrorBody(RATE_LIMIT_EXCEEDED, "请求过于频繁，请稍后再试", requestId));
                response.getWriter().flush();
                return false;
            }
            bucket.requestCount++;
            return true;
        }
    }

    private String responseRequestId() {
        return java.util.UUID.randomUUID().toString();
    }

    private boolean unauthorized(HttpServletResponse response) throws IOException {
        return reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                "未授权，请提供有效的 API Key（请求头 " + properties.getHeaderName() + "）");
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws IOException {
        String requestId = responseRequestId();
        response.setStatus(status);
        response.setHeader("X-Request-Id", requestId);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(buildErrorBody(status, message, requestId));
        response.getWriter().flush();
        return false;
    }

    private String buildErrorBody(int status, String message, String requestId) {
        return String.format(
                "{\"code\":%d,\"message\":\"%s\",\"requestId\":\"%s\",\"blockedBy\":\"security\"}",
                status,
                escapeJson(message),
                escapeJson(requestId)
        );
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static class RateBucket {
        private long windowStartMs;
        private int requestCount;
        private long exceedAt;

        private RateBucket(long windowStartMs) {
            this.windowStartMs = windowStartMs;
            this.requestCount = 0;
            this.exceedAt = 0L;
        }
    }
}
