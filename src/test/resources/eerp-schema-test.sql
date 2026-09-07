DROP TABLE IF EXISTS energy_native_quantity_snapshot;
DROP TABLE IF EXISTS energy_eerp_task;
DROP TABLE IF EXISTS energy_eerp_execution_guard;
DROP TABLE IF EXISTS energy_eerp_config;
DROP TABLE IF EXISTS energy_eerp_station_guard;
-- 电驱动水冷冷站研发计算：内容版本不可覆盖，执行租约与跨库可见性分别管理。
CREATE TABLE energy_eerp_station_guard (
  building_id VARCHAR(32) NOT NULL, station_id VARCHAR(32) NOT NULL,
  PRIMARY KEY (building_id, station_id)
);
CREATE TABLE energy_eerp_config (
  version_id VARCHAR(32) PRIMARY KEY, building_id VARCHAR(32) NOT NULL,
  station_id VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL, revision BIGINT NOT NULL,
  effective_from DATETIME(3) NOT NULL, effective_to DATETIME(3) NOT NULL,
  config_json LONGTEXT NOT NULL, relation_evidence LONGTEXT NOT NULL,
  created_by BIGINT NOT NULL, submitted_by BIGINT, approved_by BIGINT,
  created_at TIMESTAMP(3) NOT NULL, review_reason VARCHAR(500),
  INDEX idx_eerp_config_station (building_id, station_id, status, effective_from),
  CONSTRAINT chk_eerp_config_interval CHECK (effective_from < effective_to),
  CONSTRAINT chk_eerp_config_revision CHECK (revision >= 0),
  CONSTRAINT chk_eerp_config_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','ACTIVE'))
);
CREATE TABLE energy_eerp_execution_guard (guard_id INT PRIMARY KEY);
INSERT INTO energy_eerp_execution_guard (guard_id) VALUES (1);
CREATE TABLE energy_eerp_task (
  task_id VARCHAR(32) PRIMARY KEY, building_id VARCHAR(32) NOT NULL,
  station_id VARCHAR(32) NOT NULL, task_kind VARCHAR(16) NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL,
  request_json LONGTEXT NOT NULL, predecessor_task_id VARCHAR(32),
  status VARCHAR(32) NOT NULL, revision BIGINT NOT NULL, created_by BIGINT NOT NULL,
  submitted_by BIGINT, approved_by BIGINT, lease_token VARCHAR(32), lease_until TIMESTAMP(3),
  stage_json LONGTEXT, evidence_hash VARCHAR(64), failure_code VARCHAR(100),
  created_at TIMESTAMP(3) NOT NULL, trace_id VARCHAR(64) NOT NULL, review_reason VARCHAR(500),
  UNIQUE KEY uk_eerp_task_key (building_id, station_id, idempotency_key),
  INDEX idx_eerp_task_running (status, lease_until),
  INDEX idx_eerp_task_station (building_id, station_id, created_at),
  CONSTRAINT chk_eerp_task_revision CHECK (revision >= 0),
  CONSTRAINT chk_eerp_task_kind CHECK (task_kind IN ('PERIOD','ANNUAL')),
  CONSTRAINT chk_eerp_task_status CHECK (status IN ('READY','PENDING_RECALC','RUNNING','FAILED','SUCCEEDED','PENDING_SEAL','SEALED')),
  CONSTRAINT chk_eerp_task_lease CHECK (status <> 'RUNNING' OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
  CONSTRAINT chk_eerp_task_evidence CHECK (status NOT IN ('SUCCEEDED','PENDING_SEAL','SEALED') OR (stage_json IS NOT NULL AND evidence_hash IS NOT NULL))
);
CREATE TABLE energy_native_quantity_snapshot (
  snapshot_id VARCHAR(64) PRIMARY KEY, building_id VARCHAR(32) NOT NULL,
  quantity_type VARCHAR(32) NOT NULL, content_hash VARCHAR(64) NOT NULL,
  content_json LONGTEXT NOT NULL, status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  INDEX idx_native_snapshot_building (building_id, status),
  CONSTRAINT chk_native_snapshot_status CHECK (status IN ('PENDING','VISIBLE'))
);
