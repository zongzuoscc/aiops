CREATE TABLE IF NOT EXISTS ops_incident_correlation (
    fingerprint VARCHAR(191) PRIMARY KEY,
    incident_id CHAR(36) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    KEY idx_ops_correlation_expiry (expires_at)
);

CREATE TABLE IF NOT EXISTS ops_alert_event (
    id CHAR(36) PRIMARY KEY,
    source VARCHAR(64) NOT NULL,
    event_key VARCHAR(191) NOT NULL,
    fingerprint VARCHAR(191) NOT NULL,
    incident_id CHAR(36) NOT NULL,
    service_id VARCHAR(64),
    alert_type VARCHAR(191) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    title VARCHAR(255) NOT NULL,
    raw_event JSON NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_ops_alert_source_event (source, event_key),
    KEY idx_ops_alert_incident_received (incident_id, received_at),
    KEY idx_ops_alert_fingerprint_received (fingerprint, received_at),
    CONSTRAINT fk_ops_alert_incident FOREIGN KEY (incident_id) REFERENCES ops_incident(id)
);

INSERT IGNORE INTO ops_alert_event (
    id, source, event_key, fingerprint, incident_id, service_id,
    alert_type, severity, title, raw_event, received_at)
SELECT
    UUID(), source, event_key, fingerprint, id, service_id,
    LOWER(REPLACE(title, ' ', '_')), severity, title, raw_event, created_at
FROM ops_incident;

INSERT INTO ops_incident_correlation (fingerprint, incident_id, expires_at, updated_at)
SELECT incident.fingerprint,
       incident.id,
       DATE_ADD(incident.updated_at, INTERVAL 10 MINUTE),
       incident.updated_at
FROM ops_incident incident
JOIN (
    SELECT fingerprint, MAX(created_at) AS latest_created_at
    FROM ops_incident
    GROUP BY fingerprint
) latest
  ON latest.fingerprint = incident.fingerprint
 AND latest.latest_created_at = incident.created_at
ON DUPLICATE KEY UPDATE
    incident_id = VALUES(incident_id),
    expires_at = VALUES(expires_at),
    updated_at = VALUES(updated_at);
