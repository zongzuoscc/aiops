package org.example.repository;

import org.example.entity.IncidentEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IncidentEventRepository extends JpaRepository<IncidentEventEntity, String> {

    Optional<IncidentEventEntity> findFirstByOwnerIdAndExternalEventIdOrderByCreatedAtDesc(String ownerId, String externalEventId);

    List<IncidentEventEntity> findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            List<String> statuses,
            Long nextAttemptAt,
            Pageable pageable
    );

    List<IncidentEventEntity> findByOwnerIdAndStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String ownerId,
            List<String> statuses,
            Long nextAttemptAt,
            Pageable pageable
    );

    List<IncidentEventEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId, Pageable pageable);

    List<IncidentEventEntity> findByOwnerIdAndStatusOrderByCreatedAtDesc(String ownerId, String status, Pageable pageable);

    Optional<IncidentEventEntity> findByEventId(String eventId);
}
