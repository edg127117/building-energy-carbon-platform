-- 修正候选的稳定回读和隔离；不开放真实设备，不自动授予测试权限。
ALTER TABLE biz_daikin_control_grant
 ADD COLUMN relationship_hash CHAR(64),
 ADD COLUMN approved_actions TEXT;

ALTER TABLE biz_daikin_control_operation
 ADD COLUMN baseline_json TEXT,
 ADD COLUMN deadline_at BIGINT,
 ADD COLUMN stable_count INT NOT NULL DEFAULT 0,
 ADD COLUMN stable_since BIGINT,
 ADD COLUMN last_read_at BIGINT,
 ADD COLUMN unit_status VARCHAR(24),
 ADD COLUMN cloud_stable BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN lease_until BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN lease_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE biz_daikin_control_controller_slot (
 controller_key CHAR(64) PRIMARY KEY, operation_id VARCHAR(32) NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_daikin_control_sample (
 sample_id VARCHAR(32) PRIMARY KEY, operation_id VARCHAR(32) NOT NULL,
 requested_at BIGINT NOT NULL, read_at BIGINT NOT NULL,
 device_sample_at BIGINT, settings_json TEXT, matches_target BOOLEAN NOT NULL,
 reason_code VARCHAR(80), KEY ix_daikin_control_sample(operation_id,read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
