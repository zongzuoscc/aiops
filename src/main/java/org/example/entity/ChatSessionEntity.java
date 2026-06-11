package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "chat_session")
public class ChatSessionEntity {

    @Id
    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "create_time", nullable = false)
    private long createTime;

    @Column(name = "update_time", nullable = false)
    private long updateTime;

    @Column(name = "last_active_time", nullable = false)
    private long lastActiveTime;

    @Column(name = "message_pair_count", nullable = false)
    private int messagePairCount;

    @Column(name = "next_seq_no", nullable = false)
    private int nextSeqNo;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "custom_title", length = 128)
    private String customTitle;

    @Column(name = "summary_text", columnDefinition = "LONGTEXT")
    private String summaryText;

    @Column(name = "summary_updated_at")
    private Long summaryUpdatedAt;
}
