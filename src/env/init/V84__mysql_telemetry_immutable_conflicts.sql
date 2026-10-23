-- 原始事实与处置分开：首次快照不更新；日常处置只切换业务取值，能源更正沿用既有审核。
CREATE TABLE biz_telemetry_raw_intent (
    point_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_ms BIGINT NOT NULL,
    point_id VARCHAR(32) NOT NULL,
    building_id VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    storage_state VARCHAR(20) NOT NULL,
    conflict_state VARCHAR(20) NOT NULL DEFAULT 'NONE',
    conflict_revision INT NOT NULL DEFAULT 0,
    first_conflict_ms BIGINT NULL,
    selected_json LONGTEXT NULL,
    selection_version INT NOT NULL DEFAULT 0,
    next_retry_ms BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(64) NULL,
    PRIMARY KEY(point_key,event_ms),
    KEY idx_raw_intent_recovery(storage_state,next_retry_ms),
    KEY idx_raw_intent_conflict(building_id,conflict_state,first_conflict_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_telemetry_conflict_evidence (
    evidence_id VARCHAR(32) NOT NULL PRIMARY KEY,
    point_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_ms BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    snapshot_json LONGTEXT NULL,
    first_seen_ms BIGINT NOT NULL,
    last_seen_ms BIGINT NOT NULL,
    attempt_count BIGINT NOT NULL DEFAULT 1,
    UNIQUE KEY uk_telemetry_conflict_content(point_key,event_ms,payload_hash),
    KEY idx_telemetry_conflict_expiry(first_seen_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_telemetry_conflict_owner (
    building_id VARCHAR(32) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    assigned_by BIGINT NOT NULL,
    assigned_at_ms BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_telemetry_resolution (
    resolution_id VARCHAR(32) NOT NULL PRIMARY KEY,
    point_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_ms BIGINT NOT NULL,
    version_no INT NOT NULL,
    conflict_revision INT NOT NULL,
    evidence_id VARCHAR(32) NULL,
    selected_json LONGTEXT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    operator_id BIGINT NOT NULL,
    resolved_at_ms BIGINT NOT NULL,
    energy_correction_version VARCHAR(32) NULL,
    UNIQUE KEY uk_telemetry_resolution_version(point_key,event_ms,version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_telemetry_retention_boundary (
    point_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    before_ms BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_telemetry_conflict_budget (
    budget_key VARCHAR(20) NOT NULL PRIMARY KEY,
    used_rows BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO biz_telemetry_conflict_budget VALUES ('EVIDENCE',0),('MESSAGE_EVIDENCE',0),('PENDING_RAW',0),('PENDING_MESSAGE',0),('PENDING_DERIVED',0);

-- 冻结规范化批次和原接收元数据；进程退出不依赖 Broker 再次投递才能补齐回执。
CREATE TABLE biz_telemetry_message_intent (
    canonical_message_id VARCHAR(64) NOT NULL PRIMARY KEY,
    building_id VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    terminal_json LONGTEXT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_ms BIGINT NOT NULL,
    next_retry_ms BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(64) NULL,
    KEY idx_telemetry_message_recovery(state,next_retry_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_telemetry_message_conflict (
    canonical_message_id VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    materialized INT NOT NULL DEFAULT 0,
    next_retry_ms BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(64) NULL,
    first_seen_ms BIGINT NOT NULL,
    last_seen_ms BIGINT NOT NULL,
    attempt_count BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY(canonical_message_id,payload_hash),
    KEY idx_message_conflict_pending(materialized,next_retry_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
    KEY idx_telemetry_change_claim(state,next_retry_ms),
    UNIQUE KEY uk_telemetry_change_fact(point_id,event_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 只复用现有只读角色开放入口；实际处置仍须建筑范围、指定负责人和既有能源职责。
INSERT INTO sys_menu(parent_id,menu_name,menu_type,path,icon,visible,status,sort_order)
SELECT id,'报警管理','M','/operations/alarms','bell',1,1,7 FROM sys_menu root
WHERE root.path='/operations' AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE path='/operations/alarms');
INSERT INTO sys_menu(parent_id,menu_name,menu_type,path,icon,visible,status,sort_order)
SELECT id,'数据质量冲突','C','/operations/alarms/dataQuality','bell',1,1,3 FROM sys_menu parent
WHERE parent.path='/operations/alarms' AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE path='/operations/alarms/dataQuality');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.path IN('/operations/alarms','/operations/alarms/dataQuality')
WHERE r.status=1 AND r.role_key IN('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN');
