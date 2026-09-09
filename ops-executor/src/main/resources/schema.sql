CREATE TABLE IF NOT EXISTS executor_action_journal (
    request_id VARCHAR(191) PRIMARY KEY,
    state VARCHAR(16) NOT NULL,
    result_json CLOB,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS executor_nonce (
    nonce VARCHAR(191) PRIMARY KEY,
    expires_at TIMESTAMP NOT NULL
);
