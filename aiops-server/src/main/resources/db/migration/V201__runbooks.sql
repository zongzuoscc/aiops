CREATE TABLE IF NOT EXISTS ops_runbook (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    symptom_keywords VARCHAR(1000) NOT NULL,
    diagnosis_hint TEXT NOT NULL,
    recommended_action VARCHAR(64) NOT NULL,
    min_confidence DECIMAL(4, 3) NOT NULL DEFAULT 0.850,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS ops_incident_timeline (
    id CHAR(36) PRIMARY KEY,
    incident_id CHAR(36) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    details JSON,
    created_at TIMESTAMP(6) NOT NULL,
    KEY idx_ops_timeline_incident (incident_id, created_at),
    CONSTRAINT fk_ops_timeline_incident FOREIGN KEY (incident_id) REFERENCES ops_incident(id)
);

INSERT INTO ops_runbook (
    id, name, symptom_keywords, diagnosis_hint, recommended_action,
    min_confidence, enabled, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'Container crash loop',
     'exited,crash,panic,out of memory,oom,killed',
     'Confirm the exit code and recent error logs. A restart is allowed only when the configured image has not changed.',
     'RESTART_CONTAINER', 0.880, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    ('00000000-0000-0000-0000-000000000002', 'Application health check failure',
     'health,timeout,connection refused,503,unavailable',
     'Correlate container state, resource usage, logs, and HTTP health before selecting a restart.',
     'RESTART_CONTAINER', 0.900, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE name = VALUES(name), updated_at = CURRENT_TIMESTAMP(6);
