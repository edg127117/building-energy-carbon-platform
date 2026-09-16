-- 大金只读监测历史物理清理；业务默认关闭，启用后以持久租约和小批次推进。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

ALTER TABLE biz_daikin_monitor_inbox
  ADD COLUMN retention_marked_at BIGINT DEFAULT NULL COMMENT '过期载荷明确丢弃时间';
CREATE INDEX idx_daikin_inbox_retention
  ON biz_daikin_monitor_inbox(status,observed_at,retention_marked_at);
CREATE INDEX idx_daikin_state_event_retention
  ON biz_daikin_state_event(observed_at_ms,event_id);
CREATE INDEX idx_daikin_exception_retention
  ON biz_daikin_exception_instance(recovered_at_ms,exception_id);

CREATE TABLE biz_daikin_retention_job (
  job_name VARCHAR(32) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  phase VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  lease_token CHAR(36) DEFAULT NULL,
  lease_until_ms BIGINT NOT NULL DEFAULT 0,
  run_started_at_ms BIGINT DEFAULT NULL,
  last_completed_at_ms BIGINT DEFAULT NULL,
  last_error_code VARCHAR(64) DEFAULT NULL,
  td_point_cursor VARCHAR(192) COLLATE utf8mb4_bin DEFAULT NULL,
  deleted_rows BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_daikin_retention_status CHECK (status IN ('IDLE','RUNNING','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金保留期清理租约与持久阶段';

INSERT INTO biz_daikin_retention_job(job_name,phase,status)
VALUES ('DAIKIN_RETENTION','STATE_EVENT','IDLE');

CREATE TABLE biz_daikin_raw_retention_cursor (
  task_name VARCHAR(32) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  cursor_point_id VARCHAR(192) COLLATE utf8mb4_bin DEFAULT NULL,
  lease_token CHAR(36) DEFAULT NULL,
  lease_until_ms BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TDengine逐测点清理持久游标';
INSERT INTO biz_daikin_raw_retention_cursor(task_name) VALUES ('HVAC_RAW');
