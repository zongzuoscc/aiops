package org.example.incident.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncidentTimelineRepository extends JpaRepository<IncidentTimelineRecord, String> {
    List<IncidentTimelineRecord> findByIncidentIdOrderByCreatedAt(String incidentId);
}
