package org.example.incident.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findByStatusAndAvailableAtLessThanEqualOrderByCreatedAt(
            OutboxEvent.Status status,
            Instant availableAt,
            Pageable pageable);
}
