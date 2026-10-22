DROP TABLE IF EXISTS biz_telemetry_change_task;
DROP TABLE IF EXISTS biz_telemetry_message_conflict;
DROP TABLE IF EXISTS biz_telemetry_message_intent;
DROP TABLE IF EXISTS biz_telemetry_conflict_budget;
DROP TABLE IF EXISTS biz_telemetry_retention_boundary;
DROP TABLE IF EXISTS biz_telemetry_resolution;
DROP TABLE IF EXISTS biz_telemetry_conflict_owner;
DROP TABLE IF EXISTS biz_telemetry_conflict_evidence;
DROP TABLE IF EXISTS biz_telemetry_raw_intent;
-- 原始事实与处置分开：首次快照不更新；日常处置只切换业务取值，能源更正沿用既有审核。
CREATE TABLE biz_telemetry_raw_intent (
    point_key VARCHAR(32) NOT NULL,
    event_ms BIGINT NOT NULL,
    point_id VARCHAR(32) NOT NULL,
    building_id VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    snapshot_json CLOB NOT NULL,
    storage_state VARCHAR(20) NOT NULL,
    conflict_state VARCHAR(20) NOT NULL DEFAULT 'NONE',
    conflict_revision INT NOT NULL DEFAULT 0,
    first_conflict_ms BIGINT NULL,
    selected_json CLOB NULL,
    selection_version INT NOT NULL DEFAULT 0,
    next_retry_ms BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(64) NULL,
    PRIMARY KEY(point_key,event_ms)
);

CREATE TABLE biz_telemetry_conflict_evidence (
    evidence_id VARCHAR(32) NOT NULL PRIMARY KEY,
    point_key VARCHAR(32) NOT NULL,
    event_ms BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    snapshot_json CLOB NULL,
    first_seen_ms BIGINT NOT NULL,
    last_seen_ms BIGINT NOT NULL,
    attempt_count BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uk_telemetry_conflict_content UNIQUE(point_key,event_ms,payload_hash)
);

CREATE TABLE biz_telemetry_conflict_owner (
    building_id VARCHAR(32) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    assigned_by BIGINT NOT NULL,
    assigned_at_ms BIGINT NOT NULL
);

CREATE TABLE biz_telemetry_resolution (
    resolution_id VARCHAR(32) NOT NULL PRIMARY KEY,
    point_key VARCHAR(32) NOT NULL,
    event_ms BIGINT NOT NULL,
    version_no INT NOT NULL,
    conflict_revision INT NOT NULL,
    evidence_id VARCHAR(32) NULL,
    selected_json CLOB NOT NULL,
    reason VARCHAR(500) NOT NULL,
    operator_id BIGINT NOT NULL,
    resolved_at_ms BIGINT NOT NULL,
    energy_correction_version VARCHAR(32) NULL,
    CONSTRAINT uk_telemetry_resolution_version UNIQUE(point_key,event_ms,version_no)
);

CREATE TABLE biz_telemetry_retention_boundary (
    point_key VARCHAR(32) NOT NULL PRIMARY KEY,
    before_ms BIGINT NOT NULL
);

CREATE TABLE biz_telemetry_conflict_budget (
    budget_key VARCHAR(20) NOT NULL PRIMARY KEY,
    used_rows BIGINT NOT NULL
);
INSERT INTO biz_telemetry_conflict_budget VALUES ('EVIDENCE',0),('MESSAGE_EVIDENCE',0),('PENDING_RAW',0),('PENDING_MESSAGE',0),('PENDING_DERIVED',0);

-- 冻结规范化批次和原接收元数据；进程退出不依赖 Broker 再次投递才能补齐回执。
CREATE TABLE biz_telemetry_message_intent (
    canonical_message_id VARCHAR(64) NOT NULL PRIMARY KEY,
    building_id VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    snapshot_json CLOB NOT NULL,
    terminal_json CLOB NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_ms BIGINT NOT NULL,
    next_retry_ms BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(64) NULL
);

CREATE TABLE biz_telemetry_message_conflict (
    canonical_message_id VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    snapshot_json CLOB NOT NULL,
    materialized INT NOT NULL DEFAULT 0,
    next_retry_ms BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(64) NULL,
    first_seen_ms BIGINT NOT NULL,
    last_seen_ms BIGINT NOT NULL,
    attempt_count BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY(canonical_message_id,payload_hash)
);

CREATE TABLE biz_telemetry_change_task (
    task_id VARCHAR(64) NOT NULL PRIMARY KEY,
    point_id VARCHAR(32) NOT NULL,
    building_id VARCHAR(32) NOT NULL,
    event_ms BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL,
    request_revision BIGINT NOT NULL DEFAULT 1,
    next_retry_ms BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(64) NULL,
    CONSTRAINT uk_telemetry_change_fact UNIQUE(point_id,event_ms)
);
