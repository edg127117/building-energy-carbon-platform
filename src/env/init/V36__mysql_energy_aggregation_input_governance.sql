CREATE TABLE `biz_energy_meter_event` (
    `event_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `meter_point_id` VARCHAR(32) NOT NULL,
    `event_type` VARCHAR(20) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`event_id`),
    KEY `idx_energy_meter_event_point` (`building_id`,`meter_point_id`,`event_type`),
    CONSTRAINT `fk_energy_meter_event_point` FOREIGN KEY (`meter_point_id`,`building_id`)
        REFERENCES `biz_data_point` (`point_id`,`building_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_meter_event_type` CHECK
        (`event_type` IN ('RESET','ROLLOVER','REPLACEMENT','DATA_ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计量事件稳定身份';

CREATE TABLE `biz_energy_meter_event_version` (
    `event_version_id` VARCHAR(32) NOT NULL,
    `event_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `occurred_at` DATETIME(3) NOT NULL,
    `pre_event_reading` DECIMAL(30,12) NULL,
    `post_event_reading` DECIMAL(30,12) NULL,
    `rollover_modulus` DECIMAL(30,12) NULL,
    `old_meter_id` VARCHAR(64) NULL,
    `new_meter_id` VARCHAR(64) NULL,
    `relation_version_before` VARCHAR(32) NULL,
    `relation_version_after` VARCHAR(32) NULL,
    `status` VARCHAR(20) NOT NULL,
    `source_type` VARCHAR(20) NOT NULL,
    `evidence_reference` VARCHAR(500) NOT NULL,
    `simulation_flag` TINYINT(1) NOT NULL,
    `config_revision` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `approved_by` BIGINT NULL,
    `approved_at` DATETIME(3) NULL,
    `review_comment` VARCHAR(500) NULL,
    PRIMARY KEY (`event_version_id`),
    UNIQUE KEY `uk_energy_meter_event_version` (`event_id`,`version_no`),
    KEY `idx_energy_meter_event_effective` (`event_id`,`status`,`occurred_at`),
    CONSTRAINT `fk_energy_meter_event_version_identity` FOREIGN KEY (`event_id`)
        REFERENCES `biz_energy_meter_event` (`event_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_meter_event_version_values` CHECK
        (`version_no` > 0 AND `config_revision` >= 0 AND `simulation_flag` IN (0,1)),
    CONSTRAINT `chk_energy_meter_event_version_status` CHECK
        (`status` IN ('PENDING_REVIEW','APPROVED','DISABLED')),
    CONSTRAINT `chk_energy_meter_event_version_source` CHECK
        (`source_type` IN ('SIMULATION','MANUAL','DEVICE','IMPORT')),
    CONSTRAINT `chk_energy_meter_event_version_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL)),
    CONSTRAINT `chk_energy_meter_event_version_rollover` CHECK
        (`rollover_modulus` IS NULL OR `rollover_modulus` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可覆盖计量事件版本';

CREATE TABLE `biz_energy_activity_correction` (
    `correction_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `meter_point_id` VARCHAR(32) NOT NULL,
    `original_fact_identity` VARCHAR(160) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`correction_id`),
    UNIQUE KEY `uk_energy_activity_correction_fact`
        (`building_id`,`meter_point_id`,`original_fact_identity`),
    CONSTRAINT `fk_energy_activity_correction_point` FOREIGN KEY (`meter_point_id`,`building_id`)
        REFERENCES `biz_data_point` (`point_id`,`building_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动数据修正稳定身份';

CREATE TABLE `biz_energy_activity_correction_version` (
    `correction_version_id` VARCHAR(32) NOT NULL,
    `correction_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `original_value` DECIMAL(30,12) NOT NULL,
    `corrected_value` DECIMAL(30,12) NOT NULL,
    `correction_reason` VARCHAR(500) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `source_type` VARCHAR(20) NOT NULL,
    `evidence_reference` VARCHAR(500) NOT NULL,
    `quality_gate_passed` TINYINT(1) NOT NULL,
    `quality_policy_version` VARCHAR(160) NOT NULL,
    `config_revision` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `approved_by` BIGINT NULL,
    `approved_at` DATETIME(3) NULL,
    `review_comment` VARCHAR(500) NULL,
    PRIMARY KEY (`correction_version_id`),
    UNIQUE KEY `uk_energy_activity_correction_version` (`correction_id`,`version_no`),
    KEY `idx_energy_activity_correction_status` (`correction_id`,`status`),
    CONSTRAINT `fk_energy_activity_correction_version_identity` FOREIGN KEY (`correction_id`)
        REFERENCES `biz_energy_activity_correction` (`correction_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_activity_correction_version_values` CHECK
        (`version_no` > 0 AND `config_revision` >= 0 AND `quality_gate_passed` IN (0,1)),
    CONSTRAINT `chk_energy_activity_correction_version_status` CHECK
        (`status` IN ('PENDING_REVIEW','APPROVED','DISABLED')),
    CONSTRAINT `chk_energy_activity_correction_version_source` CHECK
        (`source_type` IN ('SIMULATION','MANUAL','DEVICE','IMPORT')),
    CONSTRAINT `chk_energy_activity_correction_version_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可覆盖活动数据修正版本';

CREATE TABLE `biz_energy_integration_policy` (
    `policy_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `meter_point_id` VARCHAR(32) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`policy_id`),
    UNIQUE KEY `uk_energy_integration_policy_point` (`building_id`,`meter_point_id`),
    CONSTRAINT `fk_energy_integration_policy_point` FOREIGN KEY (`meter_point_id`,`building_id`)
        REFERENCES `biz_data_point` (`point_id`,`building_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='瞬时量积分策略稳定身份';

CREATE TABLE `biz_energy_integration_policy_version` (
    `policy_version_id` VARCHAR(32) NOT NULL,
    `policy_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `integration_method` VARCHAR(24) NOT NULL,
    `maximum_gap_seconds` BIGINT NOT NULL,
    `minimum_coverage_ratio` DECIMAL(8,6) NOT NULL,
    `boundary_handling` VARCHAR(40) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `source_type` VARCHAR(20) NOT NULL,
    `evidence_reference` VARCHAR(500) NOT NULL,
    `effective_from` DATETIME(3) NOT NULL,
    `effective_to` DATETIME(3) NULL,
    `config_revision` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `approved_by` BIGINT NULL,
    `approved_at` DATETIME(3) NULL,
    `review_comment` VARCHAR(500) NULL,
    PRIMARY KEY (`policy_version_id`),
    UNIQUE KEY `uk_energy_integration_policy_version` (`policy_id`,`version_no`),
    KEY `idx_energy_integration_policy_effective`
        (`policy_id`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_energy_integration_policy_version_identity` FOREIGN KEY (`policy_id`)
        REFERENCES `biz_energy_integration_policy` (`policy_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_integration_policy_values` CHECK
        (`version_no` > 0 AND `maximum_gap_seconds` > 0
         AND `minimum_coverage_ratio` >= 0 AND `minimum_coverage_ratio` <= 1
         AND `config_revision` >= 0),
    CONSTRAINT `chk_energy_integration_policy_method` CHECK
        (`integration_method` IN ('STEP_PREVIOUS','TRAPEZOIDAL')),
    CONSTRAINT `chk_energy_integration_policy_boundary` CHECK
        (`boundary_handling`='REQUIRE_BOUNDARY_READINGS'),
    CONSTRAINT `chk_energy_integration_policy_status` CHECK
        (`status` IN ('PENDING_REVIEW','APPROVED','DISABLED')),
    CONSTRAINT `chk_energy_integration_policy_source` CHECK
        (`source_type` IN ('SIMULATION','MANUAL','STANDARD')),
    CONSTRAINT `chk_energy_integration_policy_range` CHECK
        (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_energy_integration_policy_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可覆盖瞬时量积分策略版本';
