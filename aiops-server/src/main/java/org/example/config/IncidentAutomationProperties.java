package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "incident.automation")
public class IncidentAutomationProperties {

    private boolean enabled = true;
    private boolean autoRunEnabled = true;
    private int batchSize = 5;
    private long pollIntervalMs = 8000L;
    private int maxAttempts = 3;
    private long retryBaseDelaySeconds = 30L;
    private long retryMaxDelaySeconds = 3600L;
    private boolean notifyOnUnresolved = true;
    private boolean notifyOnResolved = false;
    private String notifyWebhookUrl;
    private boolean wechatNotifyEnabled = false;
    private String wechatWebhookUrl;
    private List<String> wechatMentionedUsers = new ArrayList<>();
    private List<String> wechatMentionedMobiles = new ArrayList<>();
    private Set<String> notifySeverities = new LinkedHashSet<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoRunEnabled() {
        return autoRunEnabled;
    }

    public void setAutoRunEnabled(boolean autoRunEnabled) {
        this.autoRunEnabled = autoRunEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, Math.min(50, batchSize));
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        if (pollIntervalMs > 0) {
            this.pollIntervalMs = pollIntervalMs;
        }
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public long getRetryBaseDelaySeconds() {
        return retryBaseDelaySeconds;
    }

    public void setRetryBaseDelaySeconds(long retryBaseDelaySeconds) {
        if (retryBaseDelaySeconds > 0) {
            this.retryBaseDelaySeconds = retryBaseDelaySeconds;
        }
    }

    public long getRetryMaxDelaySeconds() {
        return retryMaxDelaySeconds;
    }

    public void setRetryMaxDelaySeconds(long retryMaxDelaySeconds) {
        if (retryMaxDelaySeconds > 0) {
            this.retryMaxDelaySeconds = retryMaxDelaySeconds;
        }
    }

    public boolean isNotifyOnUnresolved() {
        return notifyOnUnresolved;
    }

    public void setNotifyOnUnresolved(boolean notifyOnUnresolved) {
        this.notifyOnUnresolved = notifyOnUnresolved;
    }

    public boolean isNotifyOnResolved() {
        return notifyOnResolved;
    }

    public void setNotifyOnResolved(boolean notifyOnResolved) {
        this.notifyOnResolved = notifyOnResolved;
    }

    public String getNotifyWebhookUrl() {
        return notifyWebhookUrl;
    }

    public void setNotifyWebhookUrl(String notifyWebhookUrl) {
        this.notifyWebhookUrl = (notifyWebhookUrl == null || notifyWebhookUrl.isBlank()) ? null : notifyWebhookUrl.trim();
    }

    public boolean isWechatNotifyEnabled() {
        return wechatNotifyEnabled;
    }

    public void setWechatNotifyEnabled(boolean wechatNotifyEnabled) {
        this.wechatNotifyEnabled = wechatNotifyEnabled;
    }

    public String getWechatWebhookUrl() {
        return wechatWebhookUrl;
    }

    public void setWechatWebhookUrl(String wechatWebhookUrl) {
        this.wechatWebhookUrl = (wechatWebhookUrl == null || wechatWebhookUrl.isBlank()) ? null : wechatWebhookUrl.trim();
    }

    public List<String> getWechatMentionedUsers() {
        return wechatMentionedUsers;
    }

    public void setWechatMentionedUsers(List<String> wechatMentionedUsers) {
        this.wechatMentionedUsers = normalizeMentionList(wechatMentionedUsers);
    }

    public List<String> getWechatMentionedMobiles() {
        return wechatMentionedMobiles;
    }

    public void setWechatMentionedMobiles(List<String> wechatMentionedMobiles) {
        this.wechatMentionedMobiles = normalizeMentionList(wechatMentionedMobiles);
    }

    public Set<String> getNotifySeverities() {
        return notifySeverities;
    }

    public void setNotifySeverities(Set<String> notifySeverities) {
        this.notifySeverities = notifySeverities == null ? new LinkedHashSet<>() : notifySeverities;
    }

    private List<String> normalizeMentionList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> sanitized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                sanitized.add(trimmed);
            }
        }
        return sanitized;
    }

    public boolean shouldNotify(String severity) {
        if (notifySeverities == null || notifySeverities.isEmpty()) {
            return true;
        }
        if (severity == null || severity.isBlank()) {
            return true;
        }
        return notifySeverities.contains(severity.trim().toUpperCase());
    }
}
