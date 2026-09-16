-- 运行统计与分钟观测独立排队；统计口径必须由可信适配确认。
DROP TABLE IF EXISTS biz_daikin_runtime_revision;
DROP TABLE IF EXISTS biz_daikin_runtime_value;
DROP TABLE IF EXISTS biz_daikin_runtime_job;
DROP TABLE IF EXISTS biz_daikin_runtime_plan;
CREATE TABLE IF NOT EXISTS biz_daikin_runtime_plan (
  source_id VARCHAR(200) NOT NULL PRIMARY KEY,
  semantics_version VARCHAR(64) NOT NULL,
  statistics_zone VARCHAR(64) NOT NULL,
  unit VARCHAR(32) NOT NULL,
  targets_hash CHAR(64) NOT NULL,
  planned_at_ms BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS biz_daikin_runtime_job (
  job_id CHAR(64) NOT NULL PRIMARY KEY,
  source_id VARCHAR(200) NOT NULL,
  device_kind VARCHAR(16) NOT NULL,
  granularity VARCHAR(16) NOT NULL,
  period_start_ms BIGINT NOT NULL,
  period_end_ms BIGINT NOT NULL,
  statistics_zone VARCHAR(64) NOT NULL,
  unit VARCHAR(32) NOT NULL,
  semantics_version VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at_ms BIGINT NOT NULL DEFAULT 0,
  planned_at_ms BIGINT NOT NULL,
  lease_token CHAR(36) DEFAULT NULL,
  lease_until_ms BIGINT NOT NULL DEFAULT 0,
  error_code VARCHAR(64) DEFAULT NULL
);
CREATE TABLE IF NOT EXISTS biz_daikin_runtime_value (
  value_id CHAR(64) NOT NULL PRIMARY KEY,
  identity_id VARCHAR(32) NOT NULL,
  source_id VARCHAR(200) NOT NULL,
  equipment_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  space_id VARCHAR(32) DEFAULT NULL,
  system_group_id VARCHAR(32) DEFAULT NULL,
  mapping_version INT NOT NULL,
  granularity VARCHAR(16) NOT NULL,
  period_start_ms BIGINT NOT NULL,
  period_end_ms BIGINT NOT NULL,
  statistics_zone VARCHAR(64) NOT NULL,
  unit VARCHAR(32) NOT NULL,
  metrics_json TEXT DEFAULT NULL,
  revision_no INT NOT NULL DEFAULT 0,
  last_success_at_ms BIGINT DEFAULT NULL,
  last_attempt_at_ms BIGINT NOT NULL,
  last_attempt_status VARCHAR(20) NOT NULL,
  period_complete TINYINT NOT NULL DEFAULT 0,
  ownership_verified TINYINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS biz_daikin_runtime_revision (
  revision_id CHAR(64) NOT NULL PRIMARY KEY,
  value_id CHAR(64) NOT NULL,
  revision_no INT NOT NULL,
  identity_id VARCHAR(32) NOT NULL,
  source_id VARCHAR(200) NOT NULL,
  equipment_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  space_id VARCHAR(32) DEFAULT NULL,
  system_group_id VARCHAR(32) DEFAULT NULL,
  mapping_version INT NOT NULL,
  granularity VARCHAR(16) NOT NULL,
  period_start_ms BIGINT NOT NULL,
  period_end_ms BIGINT NOT NULL,
  statistics_zone VARCHAR(64) NOT NULL,
  unit VARCHAR(32) NOT NULL,
  metrics_json TEXT NOT NULL,
  period_complete TINYINT NOT NULL,
  ownership_verified TINYINT NOT NULL,
  observed_at_ms BIGINT NOT NULL
);
