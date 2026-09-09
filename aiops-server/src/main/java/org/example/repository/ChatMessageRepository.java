package org.example.repository;

import org.example.entity.ChatMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    @Query("select m from ChatMessageEntity m where m.sessionId = :sessionId order by m.seqNo desc")
    List<ChatMessageEntity> findRecentBySessionId(@Param("sessionId") String sessionId, Pageable pageable);

    @Query("select m from ChatMessageEntity m where m.sessionId = :sessionId order by m.seqNo asc")
    List<ChatMessageEntity> findBySessionIdOrderBySeqNoAsc(@Param("sessionId") String sessionId, Pageable pageable);

    @Query("select m.sessionId, max(m.createdAt), count(m.id) " +
            "from ChatMessageEntity m " +
            "where m.sessionId is not null and m.sessionId <> '' " +
            "group by m.sessionId " +
            "order by max(m.createdAt) desc")
    List<Object[]> findSessionStatsByLatest(Pageable pageable);

    void deleteBySessionId(String sessionId);
}
