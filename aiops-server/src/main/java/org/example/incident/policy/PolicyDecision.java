package org.example.incident.policy;

public record PolicyDecision(boolean allowed, PolicyDecisionCode code, String reason) {

    public static PolicyDecision allow() {
        return new PolicyDecision(true, PolicyDecisionCode.ALLOWED, "Policy checks passed");
    }

    public static PolicyDecision deny(PolicyDecisionCode code, String reason) {
        return new PolicyDecision(false, code, reason);
    }
}
