CREATE TABLE IF NOT EXISTS accounts (
    account_id INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_login_id TEXT UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('ADMIN', 'WORKER')),
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    CHECK ((role = 'ADMIN' AND admin_login_id IS NOT NULL AND length(trim(admin_login_id)) > 0)
        OR (role = 'WORKER' AND admin_login_id IS NULL)),
    CHECK (deleted_at IS NULL OR enabled = 0)
);
CREATE TABLE IF NOT EXISTS worker_profiles (
    worker_id INTEGER PRIMARY KEY REFERENCES accounts(account_id) ON DELETE RESTRICT,
    worker_name TEXT NOT NULL,
    sex TEXT CHECK (sex IS NULL OR sex IN ('M', 'F', 'U')),
    age INTEGER CHECK (age IS NULL OR age BETWEEN 0 AND 150)
);
CREATE TABLE IF NOT EXISTS safety_events (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id INTEGER NOT NULL REFERENCES worker_profiles(worker_id) ON DELETE RESTRICT,
    occurred_at INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('FALL_DETECTED', 'DANGER_ZONE_APPROACH', 'ABNORMAL_VITAL')),
    alert_sender TEXT NOT NULL,
    processed_at INTEGER,
    processed_by INTEGER REFERENCES accounts(account_id) ON DELETE RESTRICT,
    CHECK ((processed_at IS NULL AND processed_by IS NULL) OR (processed_at IS NOT NULL AND processed_by IS NOT NULL))
);
CREATE TABLE IF NOT EXISTS mobile_tokens (
    token_hash TEXT PRIMARY KEY NOT NULL CHECK (length(token_hash) = 64),
    account_id INTEGER NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
    issued_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > issued_at),
    revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_events_time ON safety_events(occurred_at, event_id);
CREATE INDEX IF NOT EXISTS idx_events_worker_time ON safety_events(worker_id, occurred_at, event_id);
CREATE INDEX IF NOT EXISTS idx_events_processed_time ON safety_events(processed_at, occurred_at, event_id);
CREATE INDEX IF NOT EXISTS idx_tokens_account ON mobile_tokens(account_id);
CREATE INDEX IF NOT EXISTS idx_tokens_expiry ON mobile_tokens(expires_at);
