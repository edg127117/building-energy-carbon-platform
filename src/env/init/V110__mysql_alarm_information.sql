-- 报警拥有独立的规则、事件、审计及建筑锁，不写采集、年度计划或碳核算表。
CREATE TABLE biz_alarm_scope (
 building_id VARCHAR(32) PRIMARY KEY,
 evaluated_at BIGINT NULL,
 device_reason TEXT NULL,
 emission_reason TEXT NULL,
 period VARCHAR(7) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_alarm_rule (
 building_id VARCHAR(32) NOT NULL,
 rule_id VARCHAR(36) NOT NULL,
 rule_type VARCHAR(16) NOT NULL,
 rule_name VARCHAR(100) NOT NULL,
 severity VARCHAR(16) NULL,
 quality_level VARCHAR(2) NOT NULL,
 space_id VARCHAR(32) NULL,
 device_category VARCHAR(64) NULL,
 enabled TINYINT NOT NULL,
 parameter_value INT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 updated_at BIGINT NULL,
 updated_by BIGINT NULL,
 recovery_cycles INT NOT NULL DEFAULT 1,
 trigger_minutes INT NOT NULL DEFAULT 0,
 recovery_minutes INT NOT NULL DEFAULT 0,
 condition_logic VARCHAR(3) NOT NULL DEFAULT 'ALL',
 classification VARCHAR(16) NULL,
 conditions_json LONGTEXT NULL,
 advanced_json LONGTEXT NULL,
 PRIMARY KEY(building_id,rule_id),
 KEY idx_alarm_rule_scope(building_id,rule_type,enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_alarm_rule_version (
 building_id VARCHAR(32) NOT NULL,
 rule_id VARCHAR(36) NOT NULL,
 rule_type VARCHAR(16) NOT NULL,
 rule_name VARCHAR(100) NOT NULL,
 severity VARCHAR(16) NULL,
 quality_level VARCHAR(2) NOT NULL,
 space_id VARCHAR(32) NULL,
 device_category VARCHAR(64) NULL,
 revision BIGINT NOT NULL,
 enabled TINYINT NOT NULL,
 parameter_value INT NOT NULL,
 changed_at BIGINT NOT NULL,
 changed_by BIGINT NULL,
 recovery_cycles INT NOT NULL DEFAULT 1,
 trigger_minutes INT NOT NULL DEFAULT 0,
 recovery_minutes INT NOT NULL DEFAULT 0,
 condition_logic VARCHAR(3) NOT NULL DEFAULT 'ALL',
 classification VARCHAR(16) NULL,
 conditions_json LONGTEXT NULL,
 advanced_json LONGTEXT NULL,
 PRIMARY KEY(building_id,rule_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 持久化每条报警规则的观测游标和连续计数，调度重放及进程重启不重复计周期。
CREATE TABLE biz_alarm_rule_state (
 building_id VARCHAR(32) NOT NULL,
 rule_id VARCHAR(36) NOT NULL,
 object_id VARCHAR(64) NOT NULL,
 last_observation_key VARCHAR(160) NULL,
 last_cycle_key VARCHAR(160) NULL,
 last_observation_at BIGINT NULL,
 trigger_since BIGINT NULL,
 recovery_cycles INT NOT NULL DEFAULT 0,
 recovery_since BIGINT NULL,
 PRIMARY KEY(building_id,rule_id,object_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_alarm_event (
 id VARCHAR(36) PRIMARY KEY,
 active_key VARCHAR(160) NULL,
 building_id VARCHAR(32) NOT NULL,
 category VARCHAR(16) NOT NULL,
 rule_id VARCHAR(36) NOT NULL,
 rule_type VARCHAR(16) NOT NULL,
 object_id VARCHAR(64) NOT NULL,
 object_name VARCHAR(150) NOT NULL,
 space_name VARCHAR(150) NULL,
 period VARCHAR(7) NULL,
 status VARCHAR(16) NOT NULL,
 severity VARCHAR(16) NOT NULL,
 description VARCHAR(500) NOT NULL,
 first_at BIGINT NOT NULL,
 last_at BIGINT NOT NULL,
 recovered_at BIGINT NULL,
 closed_at BIGINT NULL,
 closed_by BIGINT NULL,
 ack_by BIGINT NULL,
 ack_at BIGINT NULL,
 silenced_until BIGINT NULL,
 rule_revision BIGINT NOT NULL,
 evidence LONGTEXT NOT NULL,
 trigger_evidence LONGTEXT NOT NULL,
 UNIQUE KEY uk_alarm_active(active_key),
 KEY idx_alarm_building(building_id,status,first_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_alarm_event_log (
 id VARCHAR(36) PRIMARY KEY,
 event_id VARCHAR(36) NOT NULL,
 sequence_no BIGINT NOT NULL,
 at_ms BIGINT NOT NULL,
 actor BIGINT NULL,
 action VARCHAR(24) NOT NULL,
 note VARCHAR(500) NOT NULL,
 UNIQUE KEY uk_alarm_log_sequence(event_id,sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_alarm_dictionary (
 building_id VARCHAR(32) NOT NULL,
 dictionary_id VARCHAR(36) NOT NULL,
 dictionary_name VARCHAR(100) NOT NULL,
 revision BIGINT NOT NULL,
 states_json LONGTEXT NOT NULL,
 updated_at BIGINT NOT NULL,
 updated_by BIGINT NULL,
 PRIMARY KEY(building_id,dictionary_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_alarm_dictionary_version (
 building_id VARCHAR(32) NOT NULL,
 dictionary_id VARCHAR(36) NOT NULL,
 revision BIGINT NOT NULL,
 dictionary_name VARCHAR(100) NOT NULL,
 states_json LONGTEXT NOT NULL,
 updated_at BIGINT NOT NULL,
 updated_by BIGINT NULL,
 PRIMARY KEY(building_id,dictionary_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_alarm_point_dictionary (
 building_id VARCHAR(32) NOT NULL,
 point_id VARCHAR(32) NOT NULL,
 dictionary_id VARCHAR(36) NOT NULL,
 dictionary_revision BIGINT NOT NULL,
 PRIMARY KEY(building_id,point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 只补齐报警独立存储；三个既有菜单及其角色授权保持原有治理结果。
