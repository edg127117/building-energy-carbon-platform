-- 单台人工控制候选：默认没有开放设备或获授人员，不包含凭据和自动策略。
CREATE TABLE biz_daikin_control_grant (
 equipment_id VARCHAR(32) PRIMARY KEY, identity_id VARCHAR(32) NOT NULL,
 source_id VARCHAR(200) COLLATE utf8mb4_bin NOT NULL, vendor_id VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
 building_id VARCHAR(32) NOT NULL, mapping_version INT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, air_flow_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL, confirmed_by BIGINT NOT NULL, confirmed_at BIGINT NOT NULL, evidence VARCHAR(500) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_daikin_control_operation (
 operation_id VARCHAR(32) PRIMARY KEY, equipment_id VARCHAR(32) NOT NULL, building_id VARCHAR(32) NOT NULL,
 operator_id BIGINT NOT NULL, source_id VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
 physical_key CHAR(64) NOT NULL, target_json TEXT NOT NULL,
 action VARCHAR(24) NOT NULL, requested_value VARCHAR(40) NOT NULL,
 idempotency_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL, request_hash CHAR(64) NOT NULL, revision CHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL, reason_code VARCHAR(80), vendor_code VARCHAR(10), observed_value VARCHAR(40),
 before_value VARCHAR(40), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, sent_at BIGINT,
 next_check_at BIGINT, check_count INT NOT NULL DEFAULT 0, closed_reason VARCHAR(500), trace_id VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_daikin_control_request(operator_id,idempotency_key),
 KEY ix_daikin_control_device(equipment_id,created_at), KEY ix_daikin_control_due(status,next_check_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_daikin_control_slot (
 physical_key CHAR(64) PRIMARY KEY, operation_id VARCHAR(32) NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_daikin_control_source (
 source_id VARCHAR(200) COLLATE utf8mb4_bin PRIMARY KEY, paused_until BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_daikin_control_event (
 event_id VARCHAR(32) PRIMARY KEY, operation_id VARCHAR(32) NOT NULL,
 actor_id BIGINT NOT NULL, status VARCHAR(24) NOT NULL, reason_code VARCHAR(80),
 vendor_code VARCHAR(10), observed_value VARCHAR(40), operation_time BIGINT NOT NULL,
 KEY ix_daikin_control_event(operation_id,operation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_backend_duty(duty_key,duty_name,description,status,risk_level,version)
VALUES ('HVAC_DEVICE_CONTROL','空调人工控制','仅允许已开放设备的单台人工控制；不自动授予管理员','ENABLED','HIGH',1);
