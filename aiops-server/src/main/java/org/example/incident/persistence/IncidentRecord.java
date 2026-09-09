package org.example.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.example.incident.domain.IncidentStateMachine;
import org.example.incident.domain.IncidentStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ops_incident")
public class IncidentRecord {

    @Id
    private String id;
    private String source;
    @Column(name = "event_key")
    private String eventKey;
    private String fingerprint;
    @Column(name = "service_id")
    private String serviceId;
    private String severity;
    private String title;
    @Enumerated(EnumType.STRING)
    private IncidentStatus status;
    @Column(name = "raw_event", columnDefinition = "json")
    private String rawEvent;
    @Column(columnDefinition = "json")
    private String diagnosis;
    @Column(name = "policy_reason")
    private String policyReason;
    @Column(name = "retry_count")
    private int retryCount;
    @Version
    private long version;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected IncidentRecord() {
    }

    public static IncidentRecord receive(
            String source,
            String eventKey,
            String fingerprint,
            String serviceId,
            String severity,
            String title,
            String rawEvent) {
        IncidentRecord incident = new IncidentRecord();
        incident.id = UUID.randomUUID().toString();
        incident.source = source;
        incident.eventKey = eventKey;
        incident.fingerprint = fingerprint;
        incident.serviceId = serviceId;
        incident.severity = severity;
        incident.title = title;
        incident.rawEvent = rawEvent;
        incident.status = IncidentStatus.RECEIVED;
        incident.createdAt = Instant.now();
        incident.updatedAt = incident.createdAt;
        return incident;
    }

    public void transition(IncidentStateMachine stateMachine, IncidentStatus next) {
        status = stateMachine.transition(status, next);
        updatedAt = Instant.now();
        if (next == IncidentStatus.RESOLVED) {
            resolvedAt = updatedAt;
        }
    }

    public void recordDiagnosis(String diagnosis) {
        this.diagnosis = diagnosis;
        this.updatedAt = Instant.now();
    }

    public void recordPolicyReason(String policyReason) {
        this.policyReason = policyReason;
        this.updatedAt = Instant.now();
    }

    public void incrementRetry() {
        retryCount++;
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public String getRawEvent() {
        return rawEvent;
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public String getPolicyReason() {
        return policyReason;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
