package org.example.incident.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ActionExecutionRepository extends JpaRepository<ActionExecutionRecord, String> {
    Optional<ActionExecutionRecord> findByIdempotencyKey(String idempotencyKey);
    List<ActionExecutionRecord> findByIncidentIdOrderByCreatedAt(String incidentId);
    Optional<ActionExecutionRecord> findFirstByIncidentIdOrderByCreatedAtDesc(String incidentId);
}
