package org.example.incident.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface IncidentCorrelationRepository
        extends JpaRepository<IncidentCorrelationRecord, String> {

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO ops_incident_correlation (
                fingerprint, incident_id, expires_at, updated_at)
            VALUES (:fingerprint, :incidentId, :expiresAt, :now)
            """, nativeQuery = true)
    int insertIfAbsent(
            String fingerprint,
            String incidentId,
            Instant expiresAt,
            Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select correlation from IncidentCorrelationRecord correlation "
            + "where correlation.fingerprint = :fingerprint")
    Optional<IncidentCorrelationRecord> findLocked(String fingerprint);
}
