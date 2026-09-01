CREATE TABLE `biz_energy_boundary_summary_policy` (
    `policy_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `metering_boundary_id` VARCHAR(32) NOT NULL,
    `energy_item_code` VARCHAR(64) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`policy_id`),
    UNIQUE KEY `uk_energy_boundary_summary_policy`
        (`building_id`,`metering_boundary_id`,`energy_item_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计量边界汇总口径稳定身份';

CREATE TABLE `biz_energy_boundary_summary_policy_version` (
    `version_id` VARCHAR(32) NOT NULL,
    `policy_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `aggregation_mode` VARCHAR(40) NOT NULL,
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
    UNIQUE KEY `uk_energy_boundary_summary_policy_version` (`policy_id`,`version_no`),
    KEY `idx_energy_boundary_summary_policy_effective`
        (`policy_id`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_energy_boundary_summary_policy_version` FOREIGN KEY (`policy_id`)
        REFERENCES `biz_energy_boundary_summary_policy` (`policy_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_boundary_summary_mode` CHECK (`aggregation_mode` IN
        ('MAIN_METER_TOTAL','SUBMETER_SUM','INDEPENDENT_METER_SUM',
         'MAIN_WITH_SUBMETER_BREAKDOWN')),
    CONSTRAINT `chk_energy_boundary_summary_status` CHECK
        (`status` IN ('PENDING_EXPERT','APPROVED','DISABLED')),
    CONSTRAINT `chk_energy_boundary_summary_source` CHECK (`source_type`='SIMULATION'),
    CONSTRAINT `chk_energy_boundary_summary_values` CHECK
        (`version_no` > 0 AND `config_revision` >= 0),
    CONSTRAINT `chk_energy_boundary_summary_range` CHECK
        (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_energy_boundary_summary_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本化计量边界总分表汇总口径';
