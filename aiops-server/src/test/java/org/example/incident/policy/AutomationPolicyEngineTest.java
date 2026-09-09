package org.example.incident.policy;

import org.example.ops.contract.ActionType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationPolicyEngineTest {
    private final AutomationPolicyEngine policyEngine = new AutomationPolicyEngine();

    @Test
    void allowsEvidenceBackedRestartForManagedService() {
        PolicyDecision decision = policyEngine.evaluate(context(service("payment", Set.of(ActionType.RESTART_CONTAINER), false, null),
                ActionType.RESTART_CONTAINER, 2, 0.91, 0, Duration.ofMinutes(10), false, true, false));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.code()).isEqualTo(PolicyDecisionCode.ALLOWED);
    }

    @Test
    void deniesProtectedInfrastructureEvenWhenActionIsAllowed() {
        PolicyDecision decision = policyEngine.evaluate(context(service("mysql", Set.of(ActionType.RESTART_CONTAINER), true, null),
                ActionType.RESTART_CONTAINER, 3, 0.99, 0, Duration.ofHours(1), false, true, false));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo(PolicyDecisionCode.PROTECTED_TARGET);
    }

    @Test
    void deniesWritesWhenRedisSafetyStateIsUnavailable() {
        PolicyDecision decision = policyEngine.evaluate(context(service("payment", Set.of(ActionType.RESTART_CONTAINER), false, null),
                ActionType.RESTART_CONTAINER, 2, 0.95, 0, Duration.ofMinutes(10), false, false, false));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo(PolicyDecisionCode.SAFETY_STATE_UNAVAILABLE);
    }

    @Test
    void stableImageRecreationRequiresSnapshotAndRegisteredImage() {
        PolicyDecision missingSnapshot = policyEngine.evaluate(context(service("payment", Set.of(ActionType.RECREATE_WITH_STABLE_IMAGE), false, "payment:stable"),
                ActionType.RECREATE_WITH_STABLE_IMAGE, 3, 0.96, 0, Duration.ofMinutes(10), false, true, false));
        PolicyDecision complete = policyEngine.evaluate(context(service("payment", Set.of(ActionType.RECREATE_WITH_STABLE_IMAGE), false, "payment:stable"),
                ActionType.RECREATE_WITH_STABLE_IMAGE, 3, 0.96, 0, Duration.ofMinutes(10), false, true, true));
        assertThat(missingSnapshot.code()).isEqualTo(PolicyDecisionCode.ROLLBACK_UNAVAILABLE);
        assertThat(complete.allowed()).isTrue();
    }

    private PolicyContext context(ManagedServicePolicy service, ActionType action, int evidenceCount, double confidence,
                                  int recentWriteCount, Duration sinceLastWrite, boolean circuitOpen,
                                  boolean redisAvailable, boolean snapshotAvailable) {
        return new PolicyContext(service, action, evidenceCount, confidence, recentWriteCount, sinceLastWrite,
                circuitOpen, redisAvailable, snapshotAvailable);
    }

    private ManagedServicePolicy service(String name, Set<ActionType> allowedActions, boolean protectedTarget, String stableImage) {
        return new ManagedServicePolicy(name, name, allowedActions, true, protectedTarget, stableImage,
                2, Duration.ofMinutes(5), 0.8);
    }
}
