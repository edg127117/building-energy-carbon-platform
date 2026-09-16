-- 大金只读监测状态使用epoch毫秒保存绝对时间，展示时再转换为Asia/Shanghai。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

CREATE TABLE IF NOT EXISTS `biz_daikin_monitoring_target` (
    `identity_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `pending_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `equipment_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `building_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `space_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `system_group_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `mapping_version` INT NOT NULL,
    `active` TINYINT NOT NULL DEFAULT 1,
    `first_planned_at_ms` BIGINT NOT NULL,
    `last_valid_at_ms` BIGINT DEFAULT NULL,
    `last_round_id` BIGINT DEFAULT NULL,
    PRIMARY KEY (`identity_id`),
    KEY `idx_daikin_monitoring_target_stale` (`active`,`first_planned_at_ms`,`last_valid_at_ms`),
    KEY `idx_daikin_monitoring_target_source` (`source_id`,`active`),
    CONSTRAINT `chk_daikin_monitoring_target_active` CHECK (`active` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金正式监测目标及设备级新鲜度';

CREATE TABLE IF NOT EXISTS `biz_daikin_current_state` (
    `identity_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `field_name` VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `pending_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `equipment_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `building_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `space_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `system_group_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `mapping_version` INT NOT NULL,
    `raw_json` VARCHAR(1024) DEFAULT NULL,
    `normalized_value` VARCHAR(1024) DEFAULT NULL,
    `field_status` VARCHAR(16) NOT NULL,
    `last_valid_at_ms` BIGINT DEFAULT NULL,
    `last_attempt_raw_json` VARCHAR(1024) DEFAULT NULL COMMENT '本轮候选原文，缺失时为NULL',
    `last_attempt_at_ms` BIGINT NOT NULL,
    `last_attempt_building_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `last_attempt_space_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `last_attempt_system_group_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `last_attempt_mapping_version` INT NOT NULL,
    `last_round_id` BIGINT NOT NULL,
    PRIMARY KEY (`identity_id`,`field_name`),
    KEY `idx_daikin_current_state_building` (`building_id`,`identity_id`),
    CONSTRAINT `chk_daikin_current_state_status` CHECK
      (`field_status` IN ('PRESENT','MISSING','UNKNOWN','INVALID','UNCONFIRMED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金字段当前值、状态及独立新鲜度';

CREATE TABLE IF NOT EXISTS `biz_daikin_state_event` (
    `event_id` BIGINT NOT NULL AUTO_INCREMENT,
    `identity_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `field_name` VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    `round_id` BIGINT NOT NULL,
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `pending_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `equipment_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `building_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `space_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `system_group_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `mapping_version` INT NOT NULL,
    `before_raw_json` VARCHAR(1024) DEFAULT NULL,
    `before_normalized_value` VARCHAR(1024) DEFAULT NULL,
    `after_raw_json` VARCHAR(1024) DEFAULT NULL,
    `after_normalized_value` VARCHAR(1024) NOT NULL,
    `previous_observed_at_ms` BIGINT NOT NULL,
    `observed_at_ms` BIGINT NOT NULL,
    `after_gap` TINYINT NOT NULL,
    PRIMARY KEY (`event_id`),
    UNIQUE KEY `uk_daikin_state_event_round` (`identity_id`,`field_name`,`round_id`),
    KEY `idx_daikin_state_event_query` (`building_id`,`identity_id`,`observed_at_ms`),
    CONSTRAINT `chk_daikin_state_event_gap` CHECK (`after_gap` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金非温度状态变化事件';

CREATE TABLE IF NOT EXISTS `biz_daikin_exception_instance` (
    `exception_id` BIGINT NOT NULL AUTO_INCREMENT,
    `active_key` VARCHAR(512) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '仅活动实例非空并唯一',
    `exception_type` VARCHAR(40) NOT NULL,
    `scope_type` VARCHAR(16) NOT NULL,
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `identity_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `field_name` VARCHAR(100) COLLATE utf8mb4_bin DEFAULT NULL,
    `pending_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `equipment_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `building_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `space_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `system_group_id` VARCHAR(32) COLLATE utf8mb4_bin DEFAULT NULL,
    `mapping_version` INT DEFAULT NULL,
    `first_detected_at_ms` BIGINT NOT NULL,
    `last_detected_at_ms` BIGINT NOT NULL,
    `recovered_at_ms` BIGINT DEFAULT NULL,
    `last_round_id` BIGINT DEFAULT NULL,
    PRIMARY KEY (`exception_id`),
    UNIQUE KEY `uk_daikin_exception_active` (`active_key`),
    KEY `idx_daikin_exception_query` (`building_id`,`identity_id`,`first_detected_at_ms`),
    KEY `idx_daikin_exception_source` (`source_id`,`exception_type`,`recovered_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金来源、过期、厂家故障及维护异常实例';

CREATE TABLE IF NOT EXISTS `biz_daikin_source_result` (
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `last_result_round_id` BIGINT DEFAULT NULL,
    `consecutive_failure_rounds` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`source_id`),
    CONSTRAINT `chk_daikin_source_result_failures` CHECK (`consecutive_failure_rounds` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金来源计划轮结果及连续失败计数';
