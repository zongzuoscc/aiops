package org.example.incident.policy;

import org.example.ops.contract.ActionType;

public final class AutomationPolicyEngine {

    public PolicyDecision evaluate(PolicyContext context) {
        ManagedServicePolicy service = context.service();
        ActionType action = context.action();

        if (!service.automationEnabled()) {
            return PolicyDecision.deny(PolicyDecisionCode.AUTOMATION_DISABLED, "Automation is disabled");
        }
        if (service.protectedTarget()) {
            return PolicyDecision.deny(PolicyDecisionCode.PROTECTED_TARGET, "Target is protected");
        }
        if (!service.allowedActions().contains(action)) {
            return PolicyDecision.deny(PolicyDecisionCode.ACTION_NOT_ALLOWED, "Action is outside the allowlist");
        }
        if (!action.isWrite()) {
            return PolicyDecision.allow();
        }
        if (!context.safetyStateAvailable()) {
            return PolicyDecision.deny(PolicyDecisionCode.SAFETY_STATE_UNAVAILABLE,
                    "Redis safety state is unavailable");
        }
        if (context.circuitOpen()) {
            return PolicyDecision.deny(PolicyDecisionCode.CIRCUIT_OPEN, "Service circuit breaker is open");
        }
        if (context.evidenceCount() == 0) {
            return PolicyDecision.deny(PolicyDecisionCode.EVIDENCE_REQUIRED,
                    "A write action requires collected evidence");
        }
        if (context.confidence() < service.minConfidence()) {
            return PolicyDecision.deny(PolicyDecisionCode.CONFIDENCE_TOO_LOW,
                    "Diagnosis confidence is below the service threshold");
        }
        if (context.recentWriteCount() >= service.writeLimit()) {
            return PolicyDecision.deny(PolicyDecisionCode.WRITE_LIMIT_REACHED,
                    "Service write-action limit has been reached");
        }
        if (context.sinceLastWrite().compareTo(service.cooldown()) < 0) {
            return PolicyDecision.deny(PolicyDecisionCode.COOLDOWN_ACTIVE,
                    "Service is still in the write-action cooldown window");
        }
        if (action == ActionType.RECREATE_WITH_STABLE_IMAGE
                && (service.stableImage() == null || service.stableImage().isBlank()
                || !context.snapshotAvailable())) {
            return PolicyDecision.deny(PolicyDecisionCode.ROLLBACK_UNAVAILABLE,
                    "Recreate requires a stable image and a rollback snapshot");
        }
        return PolicyDecision.allow();
    }
}
