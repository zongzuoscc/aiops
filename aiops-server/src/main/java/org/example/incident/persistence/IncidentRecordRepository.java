package org.example.incident.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.example.incident.domain.IncidentStatus;

public interface IncidentRecordRepository extends JpaRepository<IncidentRecord, String> {
    Optional<IncidentRecord> findBySourceAndEventKey(String source, String eventKey);
    boolean existsByServiceIdAndStatusIn(String serviceId, Collection<IncidentStatus> statuses);

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO ops_incident (
                id, source, event_key, fingerprint, service_id, severity, title,
                status, raw_event, retry_count, version, created_at, updated_at)
            VALUES (:id, :source, :eventKey, :fingerprint, :serviceId, :severity,
                :title, 'RECEIVED', CAST(:rawEvent AS JSON), 0, 0, :now, :now)
            """, nativeQuery = true)
    int insertReceived(
            String id,
            String source,
            String eventKey,
            String fingerprint,
            String serviceId,
            String severity,
            String title,
            String rawEvent,
            Instant now);
}
