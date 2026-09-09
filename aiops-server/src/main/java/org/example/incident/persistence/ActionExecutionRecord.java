package org.example.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.example.ops.contract.ActionResult;
import org.example.ops.contract.ActionType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ops_action_execution")
public class ActionExecutionRecord {

    public enum State { PENDING, RUNNING, SUCCEEDED, FAILED, ROLLED_BACK }

    @Id
    private String id;
    @Column(name = "incident_id")
    private String incidentId;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type")
    private ActionType actionType;
    private String target;
    @Enumerated(EnumType.STRING)
    private State state;
    @Column(name = "request_payload", columnDefinition = "json")
    private String requestPayload;
    @Column(name = "result_payload", columnDefinition = "json")
    private String resultPayload;
    @Column(name = "rollback_snapshot", columnDefinition = "json")
    private String rollbackSnapshot;
    @Column(name = "error_message")
    private String errorMessage;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ActionExecutionRecord() {
    }

    public static ActionExecutionRecord pending(
            String incidentId,
            String idempotencyKey,
            ActionType action,
            String target,
            String requestPayload) {
        ActionExecutionRecord execution = new ActionExecutionRecord();
        execution.id = UUID.randomUUID().toString();
        execution.incidentId = incidentId;
        execution.idempotencyKey = idempotencyKey;
        execution.actionType = action;
        execution.target = target;
        execution.requestPayload = requestPayload;
        execution.state = State.PENDING;
        execution.createdAt = Instant.now();
        execution.updatedAt = execution.createdAt;
        return execution;
    }

    public void running() {
        state = State.RUNNING;
        startedAt = Instant.now();
        updatedAt = startedAt;
    }

    public void complete(ActionResult result, String resultPayload, String rollbackSnapshot) {
        state = result.success() ? State.SUCCEEDED : State.FAILED;
        this.resultPayload = resultPayload;
        this.rollbackSnapshot = rollbackSnapshot;
        this.errorMessage = result.success() ? null : result.message();
        finishedAt = Instant.now();
        updatedAt = finishedAt;
    }

    public void rolledBack(String resultPayload) {
        state = State.ROLLED_BACK;
        this.resultPayload = resultPayload;
        finishedAt = Instant.now();
        updatedAt = finishedAt;
    }

    public String getId() {
        return id;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public String getTarget() {
        return target;
    }

    public State getState() {
        return state;
    }

    public String getResultPayload() {
        return resultPayload;
    }

    public String getRollbackSnapshot() {
        return rollbackSnapshot;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
