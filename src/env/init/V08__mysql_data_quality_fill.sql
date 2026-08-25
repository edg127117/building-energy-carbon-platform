-- ============================================================================
-- 已有 MySQL 环境的数据质量补全增量迁移
-- 仅创建新表，不读取或改写 biz_data_point.default_value 等既有业务数据。
-- ============================================================================
USE `iot_platform`;

CREATE TABLE IF NOT EXISTS `biz_point_typical_value_config` (
    `config_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `typical_value` DECIMAL(12,4) NOT NULL,
    `unit` VARCHAR(20) NOT NULL,
    `source_description` VARCHAR(500) NOT NULL,
    `reason` VARCHAR(500) NOT NULL,
    `valid_from` DATETIME(3) NOT NULL,
    `valid_to` DATETIME(3) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL,
    `version` INT NOT NULL,
    `created_by` BIGINT NOT NULL,
    `submitted_at` DATETIME(3) DEFAULT NULL,
    `reviewer_id` BIGINT DEFAULT NULL,
    `review_comment` VARCHAR(500) DEFAULT NULL,
    `reviewed_at` DATETIME(3) DEFAULT NULL,
    `disabled_by` BIGINT DEFAULT NULL,
    `disabled_reason` VARCHAR(500) DEFAULT NULL,
    `disabled_at` DATETIME(3) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`config_id`),
    UNIQUE KEY `uk_typical_point_version` (`point_id`, `version`),
    KEY `idx_typical_building_status` (`building_id`, `status`),
    KEY `idx_typical_effective`
        (`point_id`, `status`, `valid_from`, `valid_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测点典型值审批版本';

CREATE TABLE IF NOT EXISTS `biz_data_quality_fill_task` (
    `task_id` VARCHAR(32) NOT NULL,
    `idempotency_key` VARCHAR(160) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `start_minute` DATETIME(3) NOT NULL,
    `end_minute` DATETIME(3) NOT NULL,
    `minute_count` INT NOT NULL DEFAULT 0,
    `data_quality` TINYINT NOT NULL,
    `source_type` VARCHAR(30) NOT NULL,
    `algorithm_version` VARCHAR(32) NOT NULL,
    `evidence_json` JSON NOT NULL,
    `typical_config_id` VARCHAR(32) DEFAULT NULL,
    `typical_config_version` INT DEFAULT NULL,
    `apply_status` VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    `applied_count` INT NOT NULL DEFAULT 0,
    `failed_count` INT NOT NULL DEFAULT 0,
    `replaced_count` INT NOT NULL DEFAULT 0,
    `voided_count` INT NOT NULL DEFAULT 0,
    `failed_minutes_json` JSON DEFAULT NULL,
    `retry_count` INT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `generated_at` DATETIME(3) NOT NULL,
    `closed_at` DATETIME(3) DEFAULT NULL,
    `void_by` BIGINT DEFAULT NULL,
    `void_reason` VARCHAR(500) DEFAULT NULL,
    `void_at` DATETIME(3) DEFAULT NULL,
    `supersedes_task_id` VARCHAR(32) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`task_id`),
    CONSTRAINT `chk_fill_quality` CHECK (`data_quality` IN (1, 2)),
    UNIQUE KEY `uk_fill_idempotency` (`idempotency_key`),
    KEY `idx_fill_building_range`
        (`building_id`, `start_minute`, `end_minute`),
    KEY `idx_fill_point_range`
        (`point_id`, `start_minute`, `end_minute`),
    KEY `idx_fill_status_update` (`apply_status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质量补全写入与追溯批次';
