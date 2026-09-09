package org.example.incident.policy;

import org.example.ops.contract.ActionType;

import java.time.Duration;
import java.util.Set;

public record ManagedServicePolicy(
        String serviceId,
        String containerName,
        Set<ActionType> allowedActions,
        boolean automationEnabled,
        boolean protectedTarget,
        String stableImage,
        int writeLimit,
        Duration cooldown,
        double minConfidence) {
}
