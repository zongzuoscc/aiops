package org.example.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ops_alert_event")
public class AlertEventRecord {

    @Id
    private String id;
    private String source;
    @Column(name = "event_key")
    private String eventKey;
    private String fingerprint;
    @Column(name = "incident_id")
    private String incidentId;
    @Column(name = "service_id")
    private String serviceId;
    @Column(name = "alert_type")
    private String alertType;
    private String severity;
    private String title;
    @Column(name = "raw_event", columnDefinition = "json")
    private String rawEvent;
    @Column(name = "received_at")
    private Instant receivedAt;

    protected AlertEventRecord() {
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

    public String getIncidentId() {
        return incidentId;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getAlertType() {
        return alertType;
    }

    public String getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public String getRawEvent() {
        return rawEvent;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
