package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "incident_event",
        indexes = {
                @Index(name = "idx_incident_status_next_attempt", columnList = "status, next_attempt_at"),
                @Index(name = "idx_incident_owner_status", columnList = "owner_id, status"),
                @Index(name = "idx_incident_external", columnList = "external_event_id"),
                @Index(name = "idx_incident_created", columnList = "created_at")
        }
)
public class IncidentEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "external_event_id", length = 128)
    private String externalEventId;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "source", length = 64)
    private String source;

    @Column(name = "severity", length = 16)
    private String severity;

    @Column(name = "service_name", length = 128)
    private String serviceName;

    @Column(name = "event_title", length = 256)
    private String eventTitle;

    @Column(name = "summary", length = 512)
    private String summary;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Long nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Long lastAttemptAt;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    @Column(name = "resolved_at")
    private Long resolvedAt;

    @Column(name = "payload_json", columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(name = "final_report", columnDefinition = "LONGTEXT")
    private String finalReport;

    @Column(name = "last_error", columnDefinition = "LONGTEXT")
    private String lastError;
}
