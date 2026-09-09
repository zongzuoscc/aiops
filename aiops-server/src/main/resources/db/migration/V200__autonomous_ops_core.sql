CREATE TABLE IF NOT EXISTS managed_service (
    id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    container_name VARCHAR(128) NOT NULL UNIQUE,
    health_url VARCHAR(512),
    stable_image VARCHAR(255),
    allowed_actions VARCHAR(512) NOT NULL,
    automation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    protected_target BOOLEAN NOT NULL DEFAULT FALSE,
    min_confidence DECIMAL(4, 3) NOT NULL DEFAULT 0.800,
    write_limit INT NOT NULL DEFAULT 2,
    cooldown_seconds INT NOT NULL DEFAULT 300,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS ops_incident (
    id CHAR(36) PRIMARY KEY,
    source VARCHAR(64) NOT NULL,
    event_key VARCHAR(191) NOT NULL,
    fingerprint VARCHAR(191) NOT NULL,
    service_id VARCHAR(64),
    severity VARCHAR(16) NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    raw_event JSON NOT NULL,
    diagnosis JSON,
    policy_reason VARCHAR(512),
    retry_count INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    resolved_at TIMESTAMP(6),
    UNIQUE KEY uk_ops_incident_source_event (source, event_key),
    KEY idx_ops_incident_status_updated (status, updated_at),
    KEY idx_ops_incident_fingerprint_created (fingerprint, created_at)
);

CREATE TABLE IF NOT EXISTS ops_action_execution (
    id CHAR(36) PRIMARY KEY,
    incident_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(191) NOT NULL UNIQUE,
    action_type VARCHAR(64) NOT NULL,
    target VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    request_payload JSON NOT NULL,
    result_payload JSON,
    rollback_snapshot JSON,
    error_message VARCHAR(1000),
    started_at TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    KEY idx_ops_action_incident (incident_id, created_at),
    CONSTRAINT fk_ops_action_incident FOREIGN KEY (incident_id) REFERENCES ops_incident(id)
);

CREATE TABLE IF NOT EXISTS ops_event_outbox (
    id CHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6),
    last_error VARCHAR(1000),
    KEY idx_ops_outbox_poll (status, available_at, created_at)
);
