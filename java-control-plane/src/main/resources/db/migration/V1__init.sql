-- =============================================================================
-- V1__init.sql  - Next-Gen Control Plane initial schema
--
-- Flyway picks this up automatically from classpath:db/migration/.
-- Compatible with: PostgreSQL 14+ (production), H2 2.x MODE=PostgreSQL (tests).
-- =============================================================================

-- Nodes: stores last-known state of every worker node, updated on each heartbeat
CREATE TABLE IF NOT EXISTS nodes (
    node_id             VARCHAR(255) PRIMARY KEY,
    hostname            VARCHAR(255)    NOT NULL DEFAULT '',
    ip                  VARCHAR(64)     NOT NULL DEFAULT '',
    port                INT             NOT NULL DEFAULT 0,
    status              VARCHAR(32)     NOT NULL DEFAULT 'UNKNOWN',
    agent_version       VARCHAR(128)    NOT NULL DEFAULT '',
    cpu_cores           INT             NOT NULL DEFAULT 0,
    total_memory_bytes  BIGINT          NOT NULL DEFAULT 0,
    os_name             VARCHAR(128)    NOT NULL DEFAULT '',
    cpu_usage           DOUBLE PRECISION,
    memory_usage        DOUBLE PRECISION,
    battery_percent     DOUBLE PRECISION,
    risk_score          DOUBLE PRECISION,
    last_heartbeat_ms   BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_nodes_status ON nodes (status);
CREATE INDEX IF NOT EXISTS idx_nodes_last_heartbeat ON nodes (last_heartbeat_ms);

-- Tasks: one row per task dispatched to the cluster (append-only audit record)
CREATE TABLE IF NOT EXISTS tasks (
    task_id             VARCHAR(255) PRIMARY KEY,
    job_id              VARCHAR(255),
    kind                VARCHAR(128)    NOT NULL,
    payload_json        TEXT            NOT NULL DEFAULT '{}',
    assigned_node_id    VARCHAR(255),
    state               VARCHAR(32)     NOT NULL DEFAULT 'QUEUED',
    result_json         TEXT,
    error               TEXT,
    attempt             INT             NOT NULL DEFAULT 0,
    created_at_ms       BIGINT          NOT NULL,
    dispatched_at_ms    BIGINT          NOT NULL DEFAULT 0,
    completed_at_ms     BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tasks_job_id     ON tasks (job_id);
CREATE INDEX IF NOT EXISTS idx_tasks_state      ON tasks (state);
CREATE INDEX IF NOT EXISTS idx_tasks_node       ON tasks (assigned_node_id);
CREATE INDEX IF NOT EXISTS idx_tasks_created_at ON tasks (created_at_ms DESC);

-- Jobs: one row per submitted job (one or more tasks running as a unit)
CREATE TABLE IF NOT EXISTS jobs (
    job_id              VARCHAR(255) PRIMARY KEY,
    kind                VARCHAR(128)    NOT NULL,
    state               VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    combined_result_json TEXT,
    supersedes_job_id   VARCHAR(255),
    created_at_ms       BIGINT          NOT NULL,
    updated_at_ms       BIGINT          NOT NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_jobs_state      ON jobs (state);
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON jobs (created_at_ms DESC);

-- Job-to-task join table
CREATE TABLE IF NOT EXISTS job_tasks (
    job_id   VARCHAR(255) NOT NULL,
    task_id  VARCHAR(255) NOT NULL,
    PRIMARY KEY (job_id, task_id)
);

CREATE INDEX IF NOT EXISTS idx_job_tasks_task ON job_tasks (task_id);

-- Accounts: supports email+password and GitHub OAuth device-flow
CREATE TABLE IF NOT EXISTS accounts (
    id              VARCHAR(255) PRIMARY KEY,
    email           VARCHAR(255),
    display_name    VARCHAR(255)    NOT NULL DEFAULT '',
    password_hash   TEXT,
    github_login    VARCHAR(128),
    github_id       BIGINT,
    avatar_url      TEXT,
    is_current      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_accounts_email        ON accounts (email);
CREATE INDEX IF NOT EXISTS idx_accounts_github_login ON accounts (github_login);

-- Audit log: immutable, append-only event trail (no row ever updated or deleted)
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurred_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id        VARCHAR(255),
    action          VARCHAR(128)    NOT NULL,
    entity_type     VARCHAR(64)     NOT NULL,
    entity_id       VARCHAR(255),
    details_json    TEXT            NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_audit_occurred_at ON audit_log (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_entity      ON audit_log (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_actor       ON audit_log (actor_id);

-- Enrollment tokens: node join tokens issued by the control plane
CREATE TABLE IF NOT EXISTS enrollment_tokens (
    token           VARCHAR(512) PRIMARY KEY,
    node_id_hint    VARCHAR(255),
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP       NOT NULL,
    used_at         TIMESTAMP,
    used_by_node    VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_enrollment_tokens_expires ON enrollment_tokens (expires_at);
