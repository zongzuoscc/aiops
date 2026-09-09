package org.example.repository;

import jakarta.persistence.LockModeType;
import org.example.entity.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSessionEntity s where s.sessionId = :sessionId")
    Optional<ChatSessionEntity> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

    Optional<ChatSessionEntity> findBySessionIdAndOwnerId(String sessionId, String ownerId);

    List<ChatSessionEntity> findByOwnerIdAndNextSeqNoGreaterThanOrderByLastActiveTimeDesc(
            String ownerId, int nextSeqNo, Pageable pageable);

    List<ChatSessionEntity> findByOwnerIdOrderByLastActiveTimeDesc(String ownerId, Pageable pageable);

    List<ChatSessionEntity> findByNextSeqNoGreaterThanOrderByLastActiveTimeDesc(int nextSeqNo, Pageable pageable);

    List<ChatSessionEntity> findAllByOrderByLastActiveTimeDesc(Pageable pageable);
}
