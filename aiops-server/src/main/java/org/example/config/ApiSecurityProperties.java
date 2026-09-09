package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Component
@ConfigurationProperties(prefix = "api-security")
public class ApiSecurityProperties {

    private boolean enabled = false;
    private String headerName = "X-API-KEY";
    private String keys = "";
    private String allowedOrigins = "*";
    private RateLimit rateLimit = new RateLimit();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName == null || headerName.isBlank() ? "X-API-KEY" : headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getAllowedOrigins() {
        return allowedOrigins == null ? "*" : allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public String[] getAllowedOriginArray() {
        return getAllowedOriginList().toArray(String[]::new);
    }

    public Set<String> getAllowedOriginSet() {
        return getNormalizedSet(getAllowedOrigins());
    }

    public boolean hasWildcardOrigin() {
        Set<String> origins = getAllowedOriginSet();
        return origins.isEmpty() || origins.contains("*") || origins.contains("*.*") || origins.contains("**");
    }

    private Set<String> getAllowedOriginList() {
        return getNormalizedSet(getAllowedOrigins());
    }

    public String getKeys() {
        return keys == null ? "" : keys;
    }

    public void setKeys(String keys) {
        this.keys = keys == null ? "" : keys;
    }

    public Set<String> getNormalizedKeys() {
        Set<String> normalizedKeys = new LinkedHashSet<>();
        if (keys == null) {
            return normalizedKeys;
        }
        String[] keyItems = keys.split(",");
        for (String key : keyItems) {
            if (key == null) {
                continue;
            }
            String normalized = key.trim();
            if (!normalized.isEmpty()) {
                normalizedKeys.add(normalized);
            }
        }
        return normalizedKeys;
    }

    private Set<String> getNormalizedSet(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of("*");
        }

        return Stream.of(raw.split(","))
                .map(item -> item == null ? "" : item.trim())
                .filter(item -> !item.isEmpty())
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit == null ? new RateLimit() : rateLimit;
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int requestsPerMinute = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = Math.max(1, requestsPerMinute);
        }
    }
}
