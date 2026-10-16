package org.example.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ops_incident_correlation")
public class IncidentCorrelationRecord {

    @Id
    private String fingerprint;
    @Column(name = "incident_id")
    private String incidentId;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected IncidentCorrelationRecord() {
    }

    public void assign(String incidentId, Instant expiresAt, Instant now) {
        this.incidentId = incidentId;
        this.expiresAt = expiresAt;
        this.updatedAt = now;
    }

    public void extend(Instant expiresAt, Instant now) {
        this.expiresAt = expiresAt;
        this.updatedAt = now;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
