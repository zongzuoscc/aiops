package org.example.incident.policy;

import org.example.ops.contract.ActionType;

import java.time.Duration;

public record PolicyContext(
        ManagedServicePolicy service,
        ActionType action,
        int evidenceCount,
        double confidence,
        int recentWriteCount,
        Duration sinceLastWrite,
        boolean circuitOpen,
        boolean safetyStateAvailable,
        boolean snapshotAvailable) {
}
