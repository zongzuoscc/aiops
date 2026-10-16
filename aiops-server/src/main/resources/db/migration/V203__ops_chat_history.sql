-- The autonomous profile uses Flyway rather than Hibernate schema updates.
-- Keep existing conversation tables; provision them on a fresh installation.
CREATE TABLE IF NOT EXISTS chat_session (
    session_id VARCHAR(64) PRIMARY KEY,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    last_active_time BIGINT NOT NULL,
    message_pair_count INT NOT NULL,
    next_seq_no INT NOT NULL,
    owner_id VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    custom_title VARCHAR(128),
    summary_text LONGTEXT,
    summary_updated_at BIGINT
);

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    seq_no INT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    mode VARCHAR(16) NOT NULL,
    intent VARCHAR(64),
    topic VARCHAR(64),
    memory_type VARCHAR(32),
    entities_json LONGTEXT,
    keywords_json LONGTEXT,
    created_at BIGINT NOT NULL,
    UNIQUE KEY uk_msg_session_seq (session_id, seq_no),
    KEY idx_msg_session_created (session_id, created_at),
    KEY idx_msg_session_seq (session_id, seq_no)
);
