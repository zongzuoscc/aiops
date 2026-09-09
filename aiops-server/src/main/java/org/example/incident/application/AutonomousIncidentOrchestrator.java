package org.example.incident.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.incident.diagnosis.DiagnosisPlan;
import org.example.incident.diagnosis.EvidenceBundle;
import org.example.incident.diagnosis.EvidenceCollector;
import org.example.incident.diagnosis.IncidentDiagnosisEngine;
import org.example.incident.domain.IncidentStatus;
import org.example.incident.executor.ExecutorGateway;
import org.example.incident.notification.MailIncidentNotifier;
import org.example.incident.observability.IncidentMetrics;
import org.example.incident.persistence.ActionExecutionRecord;
import org.example.incident.persistence.IncidentRecord;
import org.example.incident.persistence.ManagedServiceRecord;
import org.example.incident.persistence.ManagedServiceRepository;
import org.example.incident.policy.AutomationPolicyEngine;
import org.example.incident.policy.PolicyContext;
import org.example.incident.policy.PolicyDecision;
import org.example.incident.safety.RedisSafetyStateStore;
import org.example.ops.contract.ActionResult;
import org.example.ops.contract.ActionType;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

@Service
public class AutonomousIncidentOrchestrator {

    private static final Duration LEASE_TTL = Duration.ofMinutes(3);
    private static final Duration WRITE_WINDOW = Duration.ofHours(1);

    private final WorkflowPersistence persistence;
    private final ManagedServiceRepository services;
    private final EvidenceCollector evidenceCollector;
    private final IncidentDiagnosisEngine diagnosisEngine;
    private final AutomationPolicyEngine policyEngine;
    private final RedisSafetyStateStore safety;
    private final ExecutorGateway executor;
    private final RemediationVerifier verifier;
    private final MailIncidentNotifier notifier;
    private final IncidentMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;

    public AutonomousIncidentOrchestrator(
            WorkflowPersistence persistence,
            ManagedServiceRepository services,
            EvidenceCollector evidenceCollector,
            IncidentDiagnosisEngine diagnosisEngine,
            AutomationPolicyEngine policyEngine,
            RedisSafetyStateStore safety,
            ExecutorGateway executor,
            RemediationVerifier verifier,
            MailIncidentNotifier notifier,
            IncidentMetrics metrics,
            ObjectMapper objectMapper,
            TaskScheduler taskScheduler) {
        this.persistence = persistence;
        this.services = services;
        this.evidenceCollector = evidenceCollector;
        this.diagnosisEngine = diagnosisEngine;
        this.policyEngine = policyEngine;
        this.safety = safety;
        this.executor = executor;
        this.verifier = verifier;
        this.notifier = notifier;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.taskScheduler = taskScheduler;
    }

    public void process(String incidentId) {
        IncidentRecord initial = persistence.incident(incidentId);
        if (terminal(initial.getStatus())) {
            return;
        }
        ManagedServiceRecord service = services.findById(initial.getServiceId()).orElse(null);
        if (service == null) {
            escalate(initial, "No managed-service policy is registered for " + initial.getServiceId());
            return;
        }

        RedisSafetyStateStore.Lease lease = safety.acquireServiceLease(service.getId(), LEASE_TTL)
                .orElse(null);
        if (lease == null) {
            RedisSafetyStateStore.SafetySnapshot snapshot = safety.snapshot(service.getId(), WRITE_WINDOW);
            if (!snapshot.available()) {
                runStateMachine(incidentId, service, null, false);
            } else {
                persistence.enqueueResume(incidentId, Duration.ofSeconds(10));
            }
            return;
        }
        ScheduledFuture<?> renewal = taskScheduler.scheduleAtFixedRate(
                () -> safety.renew(lease, LEASE_TTL), Duration.ofMinutes(1));
        try {
            runStateMachine(incidentId, service, lease, true);
        } finally {
            renewal.cancel(false);
            safety.release(lease);
        }
    }

    private void runStateMachine(
            String incidentId,
            ManagedServiceRecord service,
            RedisSafetyStateStore.Lease lease,
            boolean writesPermitted) {
        EvidenceBundle evidence = null;
        while (true) {
            IncidentRecord incident = persistence.incident(incidentId);
            switch (incident.getStatus()) {
                case RECEIVED -> persistence.transition(
                        incidentId, IncidentStatus.COLLECTING_EVIDENCE,
                        "Collecting container evidence", Map.of("serviceId", service.getId()));
                case COLLECTING_EVIDENCE -> {
                    evidence = evidenceCollector.collect(
                            incidentId, service.getId(), incident.getRetryCount());
                    persistence.transition(
                            incidentId, IncidentStatus.DIAGNOSING,
                            "Evidence collection completed", evidence);
                }
                case DIAGNOSING -> {
                    if (evidence == null) {
                        evidence = evidenceCollector.collect(
                                incidentId, service.getId(), incident.getRetryCount());
                    }
                    DiagnosisPlan plan = diagnosisEngine.diagnose(incident, evidence);
                    persistence.saveDiagnosis(
                            incidentId, new StoredDiagnosis(plan, evidence.successfulObservationCount()));
                }
                case PLANNED -> persistence.transition(
                        incidentId, IncidentStatus.POLICY_CHECKING,
                        "Evaluating autonomous-action policy", Map.of());
                case POLICY_CHECKING -> {
                    StoredDiagnosis diagnosis = diagnosis(incident);
                    if (!diagnosis.plan().actionable()) {
                        persistence.denyPolicy(incidentId, diagnosis.plan().rationale());
                        notifyManual(persistence.incident(incidentId), diagnosis.plan().rationale());
                        metrics.manual();
                        return;
                    }
                    RedisSafetyStateStore.SafetySnapshot snapshot = safety.snapshot(service.getId(), WRITE_WINDOW);
                    PolicyDecision decision = policyEngine.evaluate(new PolicyContext(
                            service.toPolicy(),
                            diagnosis.plan().action(),
                            diagnosis.evidenceCount(),
                            diagnosis.plan().confidence(),
                            snapshot.recentWrites(),
                            snapshot.sinceLastWrite(),
                            snapshot.circuitOpen(),
                            snapshot.available(),
                            diagnosis.plan().action() != ActionType.RECREATE_WITH_STABLE_IMAGE
                                    || (service.getStableImage() != null && !service.getStableImage().isBlank())));
                    if (!decision.allowed()) {
                        persistence.denyPolicy(incidentId, decision.code() + ": " + decision.reason());
                        notifyManual(persistence.incident(incidentId), decision.reason());
                        metrics.manual();
                        return;
                    }
                    persistence.transition(
                            incidentId, IncidentStatus.EXECUTING,
                            "Policy approved " + diagnosis.plan().action(), decision);
                }
                case EXECUTING -> {
                    if (!writeLeaseValid(lease, writesPermitted)) {
                        escalate(incident, "Redis safety lease is unavailable before remediation execution");
                        return;
                    }
                    StoredDiagnosis diagnosis = diagnosis(incident);
                    ActionType action = diagnosis.plan().action();
                    String idempotencyKey = incidentId + ":remediation:" + action
                            + ":" + incident.getRetryCount();
                    ActionExecutionRecord execution = persistence.startAction(
                            incidentId, idempotencyKey, action, service.getId(), diagnosis.plan());
                    ActionResult result = executor.invoke(
                            idempotencyKey,
                            incidentId,
                            action,
                            service.getId(),
                            Map.of());
                    persistence.completeAction(execution.getId(), result);
                    if (!result.success()) {
                        if ("UNKNOWN".equals(result.data().get("outcome"))) {
                            escalate(incident, result.message());
                            return;
                        }
                        handleActionFailure(incident, result.message());
                        return;
                    }
                    if (!safety.recordWrite(service.getId(), WRITE_WINDOW)) {
                        persistence.timeline(
                                incidentId, "SAFETY_STATE", "FAILED",
                                "Redis write accounting failed; further actions will fail closed", Map.of());
                    }
                    persistence.transition(
                            incidentId, IncidentStatus.VERIFYING,
                            "Remediation executed; verification required", result);
                }
                case VERIFYING -> {
                    ActionExecutionRecord execution = persistence.latestAction(incidentId);
                    RemediationVerifier.VerificationResult verification = verifier.verify(
                            incidentId, service.getId(), "attempt-" + incident.getRetryCount());
                    if (verification.healthy()) {
                        if (writeLeaseValid(lease, writesPermitted)) {
                            discardVerifiedSnapshot(incident, execution, service);
                        }
                        IncidentRecord resolved = persistence.resolved(incidentId, verification);
                        metrics.resolved(Duration.between(resolved.getCreatedAt(), Instant.now()));
                        notifyResolved(resolved);
                        return;
                    }
                    if (execution.getActionType() == ActionType.RECREATE_WITH_STABLE_IMAGE
                            && execution.getRollbackSnapshot() != null) {
                        if (!writeLeaseValid(lease, writesPermitted)) {
                            escalate(incident,
                                    "Verification failed but Redis safety lease is unavailable for rollback");
                            return;
                        }
                        rollback(incident, execution, service);
                        return;
                    }
                    handleVerificationFailure(incident);
                    return;
                }
                case RETRY_SCHEDULED -> persistence.transition(
                        incidentId, IncidentStatus.COLLECTING_EVIDENCE,
                        "Retry started with fresh evidence", Map.of("retry", incident.getRetryCount()));
                case ROLLING_BACK -> {
                    if (!writeLeaseValid(lease, writesPermitted)) {
                        escalate(incident, "Redis safety lease is unavailable for rollback");
                        return;
                    }
                    rollback(incident, persistence.latestAction(incidentId), service);
                    return;
                }
                case RESOLVED, MANUAL_REQUIRED, FAILED, SUPPRESSED -> {
                    return;
                }
            }
        }
    }

    private boolean writeLeaseValid(
            RedisSafetyStateStore.Lease lease,
            boolean writesPermitted) {
        return writesPermitted && lease != null && safety.renew(lease, LEASE_TTL);
    }

    private void rollback(
            IncidentRecord incident,
            ActionExecutionRecord execution,
            ManagedServiceRecord service) {
        if (incident.getStatus() != IncidentStatus.ROLLING_BACK) {
            persistence.transition(
                    incident.getId(), IncidentStatus.ROLLING_BACK,
                    "Verification failed; restoring rollback snapshot", Map.of());
        }
        Map<String, String> snapshot = readSnapshot(execution.getRollbackSnapshot());
        ActionResult rollback = executor.invoke(
                incident.getId() + ":rollback:" + execution.getId(),
                incident.getId(),
                ActionType.RESTORE_SNAPSHOT,
                service.getId(),
                snapshot);
        persistence.markRolledBack(execution.getId(), rollback);
        metrics.rollback();
        persistence.transition(
                incident.getId(), IncidentStatus.VERIFYING,
                "Rollback completed; service left for manual review", rollback);
        IncidentRecord manual = persistence.manualRequired(
                incident.getId(), "Automated remediation failed verification and was rolled back");
        notifyManual(manual, manual.getPolicyReason());
        metrics.manual();
    }

    private void discardVerifiedSnapshot(
            IncidentRecord incident,
            ActionExecutionRecord execution,
            ManagedServiceRecord service) {
        if (execution.getActionType() != ActionType.RECREATE_WITH_STABLE_IMAGE
                || execution.getRollbackSnapshot() == null) {
            return;
        }
        Map<String, String> snapshot = readSnapshot(execution.getRollbackSnapshot());
        ActionResult result = executor.invoke(
                incident.getId() + ":discard-snapshot:" + execution.getId(),
                incident.getId(),
                ActionType.DISCARD_SNAPSHOT,
                service.getId(),
                snapshot);
        persistence.timeline(
                incident.getId(), "SNAPSHOT_CLEANUP",
                result.success() ? "SUCCESS" : "FAILED", result.message(), result);
    }

    private void handleActionFailure(IncidentRecord incident, String reason) {
        if (incident.getRetryCount() < 1) {
            persistence.scheduleRetry(incident.getId(), Duration.ofSeconds(30), reason);
        } else {
            escalate(incident, "Remediation execution failed after retry: " + reason);
        }
    }

    private void handleVerificationFailure(IncidentRecord incident) {
        if (incident.getRetryCount() < 1) {
            persistence.scheduleRetry(
                    incident.getId(), Duration.ofSeconds(30),
                    "Post-action health verification failed");
        } else {
            escalate(incident, "Post-action health verification failed after retry");
        }
    }

    private void escalate(IncidentRecord incident, String reason) {
        IncidentRecord manual = persistence.manualRequired(incident.getId(), reason);
        notifyManual(manual, reason);
        metrics.manual();
    }

    private StoredDiagnosis diagnosis(IncidentRecord incident) {
        try {
            return objectMapper.readValue(incident.getDiagnosis(), StoredDiagnosis.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored diagnosis is invalid", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readSnapshot(String value) {
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Rollback snapshot is invalid", ex);
        }
    }

    private boolean terminal(IncidentStatus status) {
        return status == IncidentStatus.RESOLVED
                || status == IncidentStatus.MANUAL_REQUIRED
                || status == IncidentStatus.FAILED
                || status == IncidentStatus.SUPPRESSED;
    }

    private void notifyManual(IncidentRecord incident, String reason) {
        try {
            notifier.manualRequired(incident, reason);
        } catch (RuntimeException ex) {
            persistence.timeline(incident.getId(), "NOTIFICATION", "FAILED",
                    "Incident email notification failed", Map.of("error", ex.getMessage()));
        }
    }

    private void notifyResolved(IncidentRecord incident) {
        try {
            notifier.resolved(incident);
        } catch (RuntimeException ex) {
            persistence.timeline(incident.getId(), "NOTIFICATION", "FAILED",
                    "Incident email notification failed", Map.of("error", ex.getMessage()));
        }
    }

    public record StoredDiagnosis(DiagnosisPlan plan, int evidenceCount) {
    }
}
