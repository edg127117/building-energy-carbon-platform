DROP TABLE IF EXISTS biz_daikin_retention_job;
DROP TABLE IF EXISTS biz_daikin_raw_retention_cursor;

ALTER TABLE biz_daikin_monitor_inbox ADD COLUMN retention_marked_at BIGINT;

CREATE TABLE biz_daikin_retention_job (
  job_name VARCHAR(32) PRIMARY KEY,
  phase VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  lease_token CHAR(36),
  lease_until_ms BIGINT NOT NULL DEFAULT 0,
  run_started_at_ms BIGINT,
  last_completed_at_ms BIGINT,
  last_error_code VARCHAR(64),
  td_point_cursor VARCHAR(192),
  deleted_rows BIGINT NOT NULL DEFAULT 0
);
INSERT INTO biz_daikin_retention_job(job_name,phase,status,lease_until_ms,deleted_rows)
VALUES ('DAIKIN_RETENTION','STATE_EVENT','IDLE',0,0);
CREATE TABLE biz_daikin_raw_retention_cursor (
  task_name VARCHAR(32) PRIMARY KEY,
  cursor_point_id VARCHAR(192),
  lease_token CHAR(36),
  lease_until_ms BIGINT NOT NULL DEFAULT 0
);
INSERT INTO biz_daikin_raw_retention_cursor(task_name) VALUES ('HVAC_RAW');
