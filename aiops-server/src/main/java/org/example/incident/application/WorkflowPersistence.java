package org.example.incident.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.incident.domain.IncidentStateMachine;
import org.example.incident.domain.IncidentStatus;
import org.example.incident.messaging.IncidentMessagingConfiguration;
import org.example.incident.persistence.ActionExecutionRecord;
import org.example.incident.persistence.ActionExecutionRepository;
import org.example.incident.persistence.IncidentRecord;
import org.example.incident.persistence.IncidentRecordRepository;
import org.example.incident.persistence.IncidentTimelineRecord;
import org.example.incident.persistence.IncidentTimelineRepository;
import org.example.incident.persistence.OutboxEvent;
import org.example.incident.persistence.OutboxEventRepository;
import org.example.ops.contract.ActionResult;
import org.example.ops.contract.ActionType;
import org.example.ops.contract.IncidentWorkMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class WorkflowPersistence {

    private final IncidentRecordRepository incidents;
    private final IncidentTimelineRepository timeline;
    private final ActionExecutionRepository actions;
    private final OutboxEventRepository outbox;
    private final IncidentStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    public WorkflowPersistence(
            IncidentRecordRepository incidents,
            IncidentTimelineRepository timeline,
            ActionExecutionRepository actions,
            OutboxEventRepository outbox,
            IncidentStateMachine stateMachine,
            ObjectMapper objectMapper) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.actions = actions;
        this.outbox = outbox;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public IncidentRecord incident(String id) {
        return incidents.findById(id).orElseThrow();
    }

    @Transactional
    public IncidentRecord transition(
            String id, IncidentStatus next, String summary, Object details) {
        IncidentRecord incident = incidents.findById(id).orElseThrow();
        incident.transition(stateMachine, next);
        timeline.save(IncidentTimelineRecord.of(
                id, next.name(), "SUCCESS", summary, json(details)));
        return incident;
    }

    @Transactional
    public IncidentRecord saveDiagnosis(String id, Object diagnosis) {
        IncidentRecord incident = incidents.findById(id).orElseThrow();
        incident.recordDiagnosis(json(diagnosis));
        incident.transition(stateMachine, IncidentStatus.PLANNED);
        timeline.save(IncidentTimelineRecord.of(
                id, "DIAGNOSIS", "SUCCESS", "Diagnosis plan generated", json(diagnosis)));
        return incident;
    }

    @Transactional
    public void denyPolicy(String id, String reason) {
        IncidentRecord incident = incidents.findById(id).orElseThrow();
        incident.recordPolicyReason(reason);
        incident.transition(stateMachine, IncidentStatus.MANUAL_REQUIRED);
        timeline.save(IncidentTimelineRecord.of(
                id, "POLICY", "DENIED", reason, "{}"));
    }

    @Transactional
    public IncidentRecord manualRequired(String id, String reason) {
        IncidentRecord incident = incidents.findById(id).orElseThrow();
        incident.recordPolicyReason(reason);
        incident.transition(stateMachine, IncidentStatus.MANUAL_REQUIRED);
        timeline.save(IncidentTimelineRecord.of(
                id, "MANUAL_REQUIRED", "ESCALATED", reason, "{}"));
        return incident;
    }

    @Transactional
    public IncidentRecord resolved(String id, Object verification) {
        IncidentRecord incident = incidents.findById(id).orElseThrow();
        incident.transition(stateMachine, IncidentStatus.RESOLVED);
        timeline.save(IncidentTimelineRecord.of(
                id, "RESOLVED", "SUCCESS", "Remediation passed post-action verification",
                json(verification)));
        return incident;
    }

    @Transactional
    public ActionExecutionRecord startAction(
            String incidentId,
            String idempotencyKey,
            ActionType action,
            String target,
            Object request) {
        ActionExecutionRecord existing = actions.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return existing;
        }
        ActionExecutionRecord execution = ActionExecutionRecord.pending(
                incidentId, idempotencyKey, action, target, json(request));
        execution.running();
        actions.save(execution);
        timeline.save(IncidentTimelineRecord.of(
                incidentId, "ACTION", "STARTED", action.name() + " started", json(request)));
        return execution;
    }

    @Transactional
    public ActionExecutionRecord completeAction(String executionId, ActionResult result) {
        ActionExecutionRecord execution = actions.findById(executionId).orElseThrow();
        execution.complete(result, json(result), json(result.rollbackSnapshot()));
        timeline.save(IncidentTimelineRecord.of(
                execution.getIncidentId(), "ACTION",
                result.success() ? "SUCCESS" : "FAILED", result.message(), json(result)));
        return execution;
    }

    @Transactional(readOnly = true)
    public ActionExecutionRecord latestAction(String incidentId) {
        return actions.findFirstByIncidentIdOrderByCreatedAtDesc(incidentId).orElseThrow();
    }

    @Transactional
    public void markRolledBack(String executionId, ActionResult rollback) {
        ActionExecutionRecord execution = actions.findById(executionId).orElseThrow();
        execution.rolledBack(json(rollback));
        timeline.save(IncidentTimelineRecord.of(
                execution.getIncidentId(), "ROLLBACK",
                rollback.success() ? "SUCCESS" : "FAILED", rollback.message(), json(rollback)));
    }

    @Transactional
    public void scheduleRetry(String incidentId, Duration delay, String reason) {
        IncidentRecord incident = incidents.findById(incidentId).orElseThrow();
        incident.incrementRetry();
        incident.transition(stateMachine, IncidentStatus.RETRY_SCHEDULED);
        IncidentWorkMessage message = new IncidentWorkMessage(
                UUID.randomUUID().toString(), incidentId, "INCIDENT_RETRY", Instant.now());
        outbox.save(OutboxEvent.create(
                "INCIDENT", incidentId, message.eventType(),
                IncidentMessagingConfiguration.ROUTING_KEY, json(message)).delay(delay));
        timeline.save(IncidentTimelineRecord.of(
                incidentId, "RETRY_SCHEDULED", "PENDING", reason,
                json(java.util.Map.of("delaySeconds", delay.toSeconds()))));
    }

    @Transactional
    public void enqueueResume(String incidentId, Duration delay) {
        IncidentWorkMessage message = new IncidentWorkMessage(
                UUID.randomUUID().toString(), incidentId, "INCIDENT_RESUME", Instant.now());
        outbox.save(OutboxEvent.create(
                "INCIDENT", incidentId, message.eventType(),
                IncidentMessagingConfiguration.ROUTING_KEY, json(message)).delay(delay));
    }

    @Transactional
    public void timeline(String incidentId, String stage, String outcome, String summary, Object details) {
        timeline.save(IncidentTimelineRecord.of(
                incidentId, stage, outcome, summary, json(details)));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
