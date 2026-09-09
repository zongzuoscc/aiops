package org.example.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.example.incident.policy.ManagedServicePolicy;
import org.example.ops.contract.ActionType;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "managed_service")
public class ManagedServiceRecord {

    @Id
    private String id;
    @Column(name = "display_name")
    private String displayName;
    @Column(name = "container_name")
    private String containerName;
    @Column(name = "health_url")
    private String healthUrl;
    @Column(name = "stable_image")
    private String stableImage;
    @Column(name = "allowed_actions")
    private String allowedActions;
    @Column(name = "automation_enabled")
    private boolean automationEnabled;
    @Column(name = "protected_target")
    private boolean protectedTarget;
    @Column(name = "min_confidence")
    private double minConfidence;
    @Column(name = "write_limit")
    private int writeLimit;
    @Column(name = "cooldown_seconds")
    private int cooldownSeconds;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ManagedServiceRecord() {
    }

    public static ManagedServiceRecord create(
            String id,
            String displayName,
            String containerName,
            String healthUrl,
            String stableImage,
            Set<ActionType> allowedActions,
            boolean automationEnabled,
            boolean protectedTarget,
            double minConfidence,
            int writeLimit,
            int cooldownSeconds) {
        ManagedServiceRecord service = new ManagedServiceRecord();
        service.id = id;
        service.displayName = displayName;
        service.containerName = containerName;
        service.healthUrl = healthUrl;
        service.stableImage = stableImage;
        service.allowedActions = allowedActions.stream().map(Enum::name).sorted()
                .collect(Collectors.joining(","));
        service.automationEnabled = automationEnabled;
        service.protectedTarget = protectedTarget;
        service.minConfidence = minConfidence;
        service.writeLimit = writeLimit;
        service.cooldownSeconds = cooldownSeconds;
        service.createdAt = Instant.now();
        service.updatedAt = service.createdAt;
        return service;
    }

    public ManagedServicePolicy toPolicy() {
        Set<ActionType> actions = Arrays.stream(allowedActions.split(","))
                .filter(value -> !value.isBlank())
                .map(ActionType::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return new ManagedServicePolicy(id, containerName, actions, automationEnabled,
                protectedTarget, stableImage, writeLimit,
                Duration.ofSeconds(cooldownSeconds), minConfidence);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getContainerName() {
        return containerName;
    }

    public String getHealthUrl() {
        return healthUrl;
    }

    public String getStableImage() {
        return stableImage;
    }

    public String getAllowedActions() {
        return allowedActions;
    }

    public boolean isAutomationEnabled() {
        return automationEnabled;
    }

    public boolean isProtectedTarget() {
        return protectedTarget;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public int getWriteLimit() {
        return writeLimit;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }
}
