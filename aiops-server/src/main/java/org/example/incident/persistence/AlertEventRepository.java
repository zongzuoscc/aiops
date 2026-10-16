package org.example.incident.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AlertEventRepository extends JpaRepository<AlertEventRecord, String> {

    Optional<AlertEventRecord> findBySourceAndEventKey(String source, String eventKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from AlertEventRecord event "
            + "where event.source = :source and event.eventKey = :eventKey")
    Optional<AlertEventRecord> findLockedBySourceAndEventKey(String source, String eventKey);

    List<AlertEventRecord> findByIncidentIdOrderByReceivedAt(String incidentId);

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO ops_alert_event (
                id, source, event_key, fingerprint, incident_id, service_id,
                alert_type, severity, title, raw_event, received_at)
            VALUES (
                :id, :source, :eventKey, :fingerprint, :incidentId, :serviceId,
                :alertType, :severity, :title, CAST(:rawEvent AS JSON), :receivedAt)
            """, nativeQuery = true)
    int insertReceived(
            String id,
            String source,
            String eventKey,
            String fingerprint,
            String incidentId,
            String serviceId,
            String alertType,
            String severity,
            String title,
            String rawEvent,
            Instant receivedAt);
}
