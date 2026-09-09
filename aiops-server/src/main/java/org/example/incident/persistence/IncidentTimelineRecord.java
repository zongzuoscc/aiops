package org.example.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ops_incident_timeline")
public class IncidentTimelineRecord {

    @Id
    private String id;
    @Column(name = "incident_id")
    private String incidentId;
    private String stage;
    private String outcome;
    private String summary;
    @Column(columnDefinition = "json")
    private String details;
    @Column(name = "created_at")
    private Instant createdAt;

    protected IncidentTimelineRecord() {
    }

    public static IncidentTimelineRecord of(
            String incidentId, String stage, String outcome, String summary, String details) {
        IncidentTimelineRecord record = new IncidentTimelineRecord();
        record.id = UUID.randomUUID().toString();
        record.incidentId = incidentId;
        record.stage = stage;
        record.outcome = outcome;
        record.summary = summary;
        record.details = details;
        record.createdAt = Instant.now();
        return record;
    }

    public String getId() {
        return id;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public String getStage() {
        return stage;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
