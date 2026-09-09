package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "chat_message",
        indexes = {
                @Index(name = "idx_msg_session_created", columnList = "session_id, created_at"),
                @Index(name = "idx_msg_session_seq", columnList = "session_id, seq_no")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_msg_session_seq", columnNames = {"session_id", "seq_no"})
        }
)
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "seq_no", nullable = false)
    private int seqNo;

    @Column(name = "role", length = 16, nullable = false)
    private String role;

    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "mode", length = 16, nullable = false)
    private String mode;

    @Column(name = "intent", length = 64)
    private String intent;

    @Column(name = "topic", length = 64)
    private String topic;

    @Column(name = "memory_type", length = 32)
    private String memoryType;

    @Lob
    @Column(name = "entities_json", columnDefinition = "LONGTEXT")
    private String entitiesJson;

    @Lob
    @Column(name = "keywords_json", columnDefinition = "LONGTEXT")
    private String keywordsJson;

    @Column(name = "created_at", nullable = false)
    private long createdAt;
}
