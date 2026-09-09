package org.example.incident.application;

import org.example.incident.executor.ExecutorGateway;
import org.example.ops.contract.ActionResult;
import org.example.ops.contract.ActionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RemediationVerifier {

    private final ExecutorGateway executor;
    private final int maxAttempts;
    private final long intervalMillis;

    public RemediationVerifier(
            ExecutorGateway executor,
            @Value("${aiops.autonomous.verification.attempts:15}") int maxAttempts,
            @Value("${aiops.autonomous.verification.interval-millis:2000}") long intervalMillis) {
        this.executor = executor;
        this.maxAttempts = maxAttempts;
        this.intervalMillis = intervalMillis;
    }

    public VerificationResult verify(String incidentId, String serviceId, String phase) {
        VerificationResult result = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            result = verifyOnce(incidentId, serviceId, phase + ":attempt-" + attempt);
            if (result.healthy() || attempt == maxAttempts) {
                return result;
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return result;
            }
        }
        throw new IllegalStateException("Verification attempts must be positive");
    }

    private VerificationResult verifyOnce(String incidentId, String serviceId, String phase) {
        ActionResult state = executor.invoke(
                incidentId + ":verify:" + phase + ":state",
                incidentId, ActionType.GET_CONTAINER_STATE, serviceId, Map.of());
        ActionResult health = executor.invoke(
                incidentId + ":verify:" + phase + ":health",
                incidentId, ActionType.CHECK_HTTP_HEALTH, serviceId, Map.of());

        boolean running = state.success() && Boolean.TRUE.equals(state.data().get("running"));
        Object statusValue = health.data().get("statusCode");
        int statusCode = statusValue instanceof Number number ? number.intValue() : 0;
        boolean healthy = health.success() && statusCode >= 200 && statusCode < 400;
        return new VerificationResult(running && healthy, state, health);
    }

    public record VerificationResult(boolean healthy, ActionResult state, ActionResult health) {
    }
}
