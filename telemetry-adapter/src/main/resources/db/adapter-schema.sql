CREATE DATABASE IF NOT EXISTS `iot_adapter`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `iot_adapter`;

CREATE TABLE IF NOT EXISTS `iot_protocol_profile` (
    `profile_id` VARCHAR(32) NOT NULL,
    `profile_code` VARCHAR(50) NOT NULL,
    `profile_version` INT NOT NULL,
    `source_topic` VARCHAR(200) NOT NULL,
    `device_identity_type` VARCHAR(20) NOT NULL,
    `device_identity_path` VARCHAR(200) NOT NULL,
    `protocol_version_path` VARCHAR(200) DEFAULT NULL,
    `expected_protocol_version` VARCHAR(50) DEFAULT NULL,
    `timestamp_path` VARCHAR(200) DEFAULT NULL,
    `seq_path` VARCHAR(200) DEFAULT NULL,
    `enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`profile_id`),
    UNIQUE KEY `uk_protocol_profile_version` (`profile_code`, `profile_version`),
    KEY `idx_protocol_profile_topic` (`source_topic`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='云端设备协议模板';

CREATE TABLE IF NOT EXISTS `iot_protocol_field_mapping` (
    `mapping_id` VARCHAR(32) NOT NULL,
    `profile_id` VARCHAR(32) NOT NULL,
    `source_path` VARCHAR(200) NOT NULL,
    `metric_code` VARCHAR(100) NOT NULL,
    `value_type` VARCHAR(20) NOT NULL DEFAULT 'DECIMAL',
    `source_unit` VARCHAR(50) NOT NULL,
    `target_unit` VARCHAR(50) NOT NULL,
    `scale` DECIMAL(20,9) NOT NULL DEFAULT 1,
    `offset_value` DECIMAL(20,9) NOT NULL DEFAULT 0,
    `required_flag` TINYINT(1) NOT NULL DEFAULT 1,
    `enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `sort_order` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`mapping_id`),
    UNIQUE KEY `uk_profile_source_path` (`profile_id`, `source_path`),
    UNIQUE KEY `uk_profile_metric_code` (`profile_id`, `metric_code`),
    CONSTRAINT `fk_field_mapping_profile`
        FOREIGN KEY (`profile_id`) REFERENCES `iot_protocol_profile` (`profile_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='云端协议字段映射';
