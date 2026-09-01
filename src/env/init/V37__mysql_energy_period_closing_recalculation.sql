CREATE TABLE `biz_energy_period_policy` (
    `policy_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`policy_id`),
    UNIQUE KEY `uk_energy_period_policy_building` (`building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建筑周期口径稳定身份';

CREATE TABLE `biz_energy_period_policy_version` (
    `version_id` VARCHAR(32) NOT NULL,
    `policy_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `timezone_id` VARCHAR(64) NOT NULL,
    `closing_delay_hours` INT NOT NULL,
    `lock_mode` VARCHAR(24) NOT NULL,
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
    PRIMARY KEY (`version_id`),
    UNIQUE KEY `uk_energy_period_policy_version` (`policy_id`,`version_no`),
    KEY `idx_energy_period_policy_effective`
        (`policy_id`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_energy_period_policy_version_identity` FOREIGN KEY (`policy_id`)
        REFERENCES `biz_energy_period_policy` (`policy_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_period_policy_values` CHECK
        (`version_no` > 0 AND `closing_delay_hours` BETWEEN 0 AND 720 AND `config_revision` >= 0),
    CONSTRAINT `chk_energy_period_policy_lock_mode` CHECK (`lock_mode`='REVIEW_REQUIRED'),
    CONSTRAINT `chk_energy_period_policy_status` CHECK
        (`status` IN ('PENDING_REVIEW','APPROVED','DISABLED')),
    CONSTRAINT `chk_energy_period_policy_source` CHECK (`source_type`='SIMULATION'),
    CONSTRAINT `chk_energy_period_policy_range` CHECK
        (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_energy_period_policy_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本化自然周期与封账延迟研发配置';

CREATE TABLE `biz_energy_lock_exception_policy` (
    `policy_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `issue_code` VARCHAR(64) NOT NULL,
    `applicable_scope` VARCHAR(64) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`policy_id`),
    UNIQUE KEY `uk_energy_lock_exception_identity`
        (`building_id`,`issue_code`,`applicable_scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='封账例外策略稳定身份';

CREATE TABLE `biz_energy_lock_exception_policy_version` (
    `version_id` VARCHAR(32) NOT NULL,
    `policy_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `severity` VARCHAR(16) NOT NULL,
    `lock_action` VARCHAR(40) NOT NULL,
    `maximum_affected_count` INT NULL,
    `maximum_affected_ratio` DECIMAL(8,6) NULL,
    `minimum_coverage_ratio` DECIMAL(8,6) NULL,
    `requires_approval` TINYINT(1) NOT NULL,
    `required_evidence` VARCHAR(500) NOT NULL,
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
    PRIMARY KEY (`version_id`),
    UNIQUE KEY `uk_energy_lock_exception_version` (`policy_id`,`version_no`),
    KEY `idx_energy_lock_exception_effective`
        (`policy_id`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_energy_lock_exception_version_identity` FOREIGN KEY (`policy_id`)
        REFERENCES `biz_energy_lock_exception_policy` (`policy_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_lock_exception_count` CHECK
        (`maximum_affected_count` IS NULL OR `maximum_affected_count` >= 0),
    CONSTRAINT `chk_energy_lock_exception_ratio` CHECK
        ((`maximum_affected_ratio` IS NULL OR `maximum_affected_ratio` BETWEEN 0 AND 1)
         AND (`minimum_coverage_ratio` IS NULL OR `minimum_coverage_ratio` BETWEEN 0 AND 1)),
    CONSTRAINT `chk_energy_lock_exception_action` CHECK (`lock_action` IN
        ('BLOCK','ALLOW_WITH_EXCEPTION','EXCLUDE_AFFECTED_AND_LOCK','LOCK_NATIVE_QUANTITY_ONLY')),
    CONSTRAINT `chk_energy_lock_exception_status` CHECK
        (`status` IN ('PENDING_REVIEW','APPROVED','DISABLED')),
    CONSTRAINT `chk_energy_lock_exception_source` CHECK (`source_type`='SIMULATION'),
    CONSTRAINT `chk_energy_lock_exception_values` CHECK
        (`version_no` > 0 AND `requires_approval` IN (0,1) AND `config_revision` >= 0),
    CONSTRAINT `chk_energy_lock_exception_range` CHECK
        (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_energy_lock_exception_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可覆盖封账例外策略版本';

CREATE TABLE `biz_energy_period_result_current` (
    `projection_id` VARCHAR(32) NOT NULL,
    `result_key` CHAR(64) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `period_type` VARCHAR(12) NOT NULL,
    `period_start` DATETIME(3) NOT NULL,
    `period_end` DATETIME(3) NOT NULL,
    `timezone_id` VARCHAR(64) NOT NULL,
    `period_policy_version_id` VARCHAR(32) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `revision` BIGINT NOT NULL,
    `result_nature` VARCHAR(32) NOT NULL,
    `energy_item_code` VARCHAR(64) NOT NULL,
    `native_quantity` DECIMAL(30,12) NOT NULL,
    `native_unit_code` VARCHAR(64) NOT NULL,
    `tce_value` DECIMAL(30,12) NULL,
    `tce_unit_code` VARCHAR(64) NULL,
    `coverage_ratio` DECIMAL(8,6) NOT NULL,
    `issue_codes` VARCHAR(1000) NOT NULL,
    `evidence_json` TEXT NOT NULL,
    `evidence_hash` CHAR(64) NOT NULL,
    `conversion_selection_json` VARCHAR(1000) NULL,
    `activity_watermark` DATETIME(3) NOT NULL,
    `calculated_at` DATETIME(3) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`projection_id`),
    UNIQUE KEY `uk_energy_period_current_scope`
        (`building_id`,`point_id`,`period_type`,`period_start`,`period_end`),
    UNIQUE KEY `uk_energy_period_current_result_key` (`result_key`),
    CONSTRAINT `chk_energy_period_current_type` CHECK (`period_type` IN ('DAY','MONTH','YEAR')),
    CONSTRAINT `chk_energy_period_current_status` CHECK (`status` IN ('OPEN','PROVISIONAL')),
    CONSTRAINT `chk_energy_period_current_values` CHECK
        (`revision` > 0 AND `native_quantity` >= 0 AND `coverage_ratio` BETWEEN 0 AND 1
         AND `period_end` > `period_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开放期唯一当前投影索引';

CREATE TABLE `biz_energy_period_lock_request` (
    `request_id` VARCHAR(32) NOT NULL,
    `projection_id` VARCHAR(32) NOT NULL,
    `projection_revision` BIGINT NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `issue_policy_versions` VARCHAR(1000) NOT NULL,
    `reason` VARCHAR(500) NOT NULL,
    `evidence_reference` VARCHAR(500) NOT NULL,
    `submitted_by` BIGINT NOT NULL,
    `submitted_at` DATETIME(3) NOT NULL,
    `reviewed_by` BIGINT NULL,
    `reviewed_at` DATETIME(3) NULL,
    `review_comment` VARCHAR(500) NULL,
    PRIMARY KEY (`request_id`),
    KEY `idx_energy_period_lock_projection` (`projection_id`,`status`),
    CONSTRAINT `chk_energy_period_lock_status` CHECK
        (`status` IN ('PENDING_REVIEW','APPROVED','REJECTED')),
    CONSTRAINT `chk_energy_period_lock_review` CHECK
        (`status`='PENDING_REVIEW' OR (`reviewed_by` IS NOT NULL AND `reviewed_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='月度封账提交与审核';

CREATE TABLE `biz_energy_period_result_snapshot` (
    `snapshot_id` VARCHAR(32) NOT NULL,
    `result_key` CHAR(64) NOT NULL,
    `projection_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `period_type` VARCHAR(12) NOT NULL,
    `period_start` DATETIME(3) NOT NULL,
    `period_end` DATETIME(3) NOT NULL,
    `timezone_id` VARCHAR(64) NOT NULL,
    `period_policy_version_id` VARCHAR(32) NOT NULL,
    `snapshot_version` INT NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `result_nature` VARCHAR(32) NOT NULL,
    `energy_item_code` VARCHAR(64) NOT NULL,
    `native_quantity` DECIMAL(30,12) NOT NULL,
    `native_unit_code` VARCHAR(64) NOT NULL,
    `tce_value` DECIMAL(30,12) NULL,
    `tce_unit_code` VARCHAR(64) NULL,
    `coverage_ratio` DECIMAL(8,6) NOT NULL,
    `issue_codes` VARCHAR(1000) NOT NULL,
    `exception_policy_versions` VARCHAR(1000) NOT NULL,
    `evidence_json` TEXT NOT NULL,
    `evidence_hash` CHAR(64) NOT NULL,
    `conversion_selection_json` VARCHAR(1000) NULL,
    `activity_watermark` DATETIME(3) NOT NULL,
    `supersedes_snapshot_id` VARCHAR(32) NULL,
    `source_batch_id` VARCHAR(32) NULL,
    `locked_by` BIGINT NOT NULL,
    `locked_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`snapshot_id`),
    UNIQUE KEY `uk_energy_period_snapshot_version` (`projection_id`,`snapshot_version`),
    UNIQUE KEY `uk_energy_period_snapshot_result_key` (`result_key`),
    KEY `idx_energy_period_snapshot_visible`
        (`building_id`,`point_id`,`period_type`,`period_start`,`status`,`source_batch_id`),
    CONSTRAINT `chk_energy_period_snapshot_status` CHECK (`status` IN
        ('LOCKED_COMPLETE','LOCKED_WITH_EXCEPTIONS','LOCKED_PARTIAL','SUPERSEDED','INVALIDATED')),
    CONSTRAINT `chk_energy_period_snapshot_values` CHECK
        (`snapshot_version` > 0 AND `native_quantity` >= 0 AND `coverage_ratio` BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='封账后不可覆盖结果快照索引';

CREATE TABLE `biz_energy_recalculation_batch` (
    `batch_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `idempotency_key` VARCHAR(160) NOT NULL,
    `request_hash` CHAR(64) NOT NULL,
    `mode` VARCHAR(32) NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `reason` VARCHAR(500) NOT NULL,
    `total_items` INT NOT NULL,
    `processed_items` INT NOT NULL DEFAULT 0,
    `changed_items` INT NOT NULL DEFAULT 0,
    `unchanged_items` INT NOT NULL DEFAULT 0,
    `failed_items` INT NOT NULL DEFAULT 0,
    `submitted_by` BIGINT NOT NULL,
    `submitted_at` DATETIME(3) NOT NULL,
    `approved_by` BIGINT NULL,
    `approved_at` DATETIME(3) NULL,
    `review_comment` VARCHAR(500) NULL,
    `safe_error` VARCHAR(160) NULL,
    `completed_at` DATETIME(3) NULL,
    PRIMARY KEY (`batch_id`),
    UNIQUE KEY `uk_energy_recalculation_idempotency` (`idempotency_key`),
    KEY `idx_energy_recalculation_claim` (`status`,`submitted_at`),
    CONSTRAINT `chk_energy_recalculation_mode` CHECK
        (`mode` IN ('SAME_RULES','HISTORICAL_RESTATEMENT')),
    CONSTRAINT `chk_energy_recalculation_status` CHECK (`status` IN
        ('CREATED','PENDING_REVIEW','VALIDATING','CALCULATING','WRITING_RESULTS','COMPLETED','FAILED')),
    CONSTRAINT `chk_energy_recalculation_counts` CHECK
        (`total_items` BETWEEN 1 AND 100 AND `processed_items` >= 0
         AND `changed_items` >= 0 AND `unchanged_items` >= 0 AND `failed_items` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='低频有界周期结果重算批次';

CREATE TABLE `biz_energy_recalculation_batch_item` (
    `item_id` VARCHAR(32) NOT NULL,
    `batch_id` VARCHAR(32) NOT NULL,
    `source_snapshot_id` VARCHAR(32) NOT NULL,
    `item_order` INT NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `new_snapshot_id` VARCHAR(32) NULL,
    `safe_error` VARCHAR(160) NULL,
    PRIMARY KEY (`item_id`),
    UNIQUE KEY `uk_energy_recalculation_item` (`batch_id`,`source_snapshot_id`),
    UNIQUE KEY `uk_energy_recalculation_order` (`batch_id`,`item_order`),
    CONSTRAINT `fk_energy_recalculation_item_batch` FOREIGN KEY (`batch_id`)
        REFERENCES `biz_energy_recalculation_batch` (`batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_recalculation_item_status` CHECK
        (`status` IN ('PENDING','CHANGED','UNCHANGED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='重算批次目标与断点';

CREATE TABLE `biz_energy_dirty_period` (
    `dirty_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `period_type` VARCHAR(12) NOT NULL,
    `period_start` DATETIME(3) NOT NULL,
    `period_end` DATETIME(3) NOT NULL,
    `reason_codes` VARCHAR(1000) NOT NULL,
    `event_count` INT NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `first_detected_at` DATETIME(3) NOT NULL,
    `last_detected_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`dirty_id`),
    UNIQUE KEY `uk_energy_dirty_period`
        (`building_id`,`point_id`,`period_type`,`period_start`,`period_end`),
    CONSTRAINT `chk_energy_dirty_period_status` CHECK (`status` IN ('PENDING','BATCHED','RESOLVED')),
    CONSTRAINT `chk_energy_dirty_period_count` CHECK (`event_count` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按周期去重合并的待重算集合';

INSERT INTO `sys_backend_duty`
    (`duty_key`,`duty_name`,`description`,`status`,`risk_level`,`version`)
VALUES
    ('ENERGY_LOCK_SUBMIT','能源封账提交','提交月度能源结果封账申请','ENABLED','HIGH',1),
    ('ENERGY_LOCK_APPROVE','能源封账审核','审核并形成不可覆盖月度封账快照','ENABLED','CRITICAL',1),
    ('ENERGY_EXCEPTION_APPROVE','能源封账例外审核','审核版本化封账例外白名单','ENABLED','CRITICAL',1),
    ('ENERGY_RECALC_SUBMIT','能源重算提交','提交有界周期结果重算批次','ENABLED','HIGH',1),
    ('ENERGY_RECALC_APPROVE','能源重算审核','审核并执行有界周期结果重算批次','ENABLED','CRITICAL',1)
ON DUPLICATE KEY UPDATE
    `duty_name`=VALUES(`duty_name`),`description`=VALUES(`description`),
    `status`=VALUES(`status`),`risk_level`=VALUES(`risk_level`),`version`=VALUES(`version`);
