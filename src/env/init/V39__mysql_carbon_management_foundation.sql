-- 碳管理首版：排放因子、年度分母、确定性计算、候选结果和持久化自动重算。
-- 迁移只发布固定算法元数据，不预置任何排放因子、建筑面积或常驻人数。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

CREATE TABLE `biz_carbon_factor_source` (
    `source_id` VARCHAR(32) NOT NULL,
    `source_code` VARCHAR(64) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`source_id`),
    UNIQUE KEY `uk_carbon_factor_source_code` (`source_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排放因子来源稳定身份';

CREATE TABLE `biz_carbon_factor_source_version` (
    `source_version_id` VARCHAR(32) NOT NULL,
    `source_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `source_name` VARCHAR(200) NOT NULL,
    `publisher` VARCHAR(200) NOT NULL,
    `document_reference` VARCHAR(500) NOT NULL,
    `publication_year` INT NULL,
    `published_on` DATE NULL,
    `applicability_note` VARCHAR(1000) NOT NULL,
    `evidence_reference` VARCHAR(1000) NOT NULL,
    `usage_nature` VARCHAR(32) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`source_version_id`),
    UNIQUE KEY `uk_carbon_factor_source_version` (`source_id`,`version_no`),
    CONSTRAINT `fk_carbon_source_version_identity` FOREIGN KEY (`source_id`)
        REFERENCES `biz_carbon_factor_source` (`source_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_source_version_no` CHECK (`version_no` > 0),
    CONSTRAINT `chk_carbon_source_nature` CHECK (`usage_nature` IN
        ('DEVELOPMENT_REFERENCE','FORMAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排放因子来源不可覆盖版本';

CREATE TABLE `biz_carbon_formula_version` (
    `formula_version_id` VARCHAR(32) NOT NULL,
    `formula_code` VARCHAR(64) NOT NULL,
    `version_no` INT NOT NULL,
    `algorithm_code` VARCHAR(100) NOT NULL,
    `result_basis` VARCHAR(32) NOT NULL,
    `gas_coverage` VARCHAR(100) NOT NULL,
    `usage_nature` VARCHAR(32) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `effective_from` DATETIME(3) NOT NULL,
    `effective_to` DATETIME(3) NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`formula_version_id`),
    UNIQUE KEY `uk_carbon_formula_version` (`formula_code`,`version_no`),
    KEY `idx_carbon_formula_active` (`formula_code`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `chk_carbon_formula_values` CHECK (`version_no` > 0 AND
        `result_basis` IN ('GAS_MASS','CO2E_DIRECT') AND
        `usage_nature` IN ('DEVELOPMENT_REFERENCE','FORMAL') AND
        `status` IN ('ACTIVE','DISABLED') AND
        (`effective_to` IS NULL OR `effective_to` > `effective_from`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码注册碳核算公式版本';

CREATE TABLE `biz_carbon_gwp_version` (
    `gwp_version_id` VARCHAR(32) NOT NULL,
    `gwp_code` VARCHAR(64) NOT NULL,
    `version_no` INT NOT NULL,
    `gas_code` VARCHAR(32) NOT NULL,
    `gwp_value` DECIMAL(38,18) NOT NULL,
    `source_reference` VARCHAR(500) NOT NULL,
    `usage_nature` VARCHAR(32) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `effective_from` DATETIME(3) NOT NULL,
    `effective_to` DATETIME(3) NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`gwp_version_id`),
    UNIQUE KEY `uk_carbon_gwp_version` (`gwp_code`,`version_no`),
    KEY `idx_carbon_gwp_active` (`gas_code`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `chk_carbon_gwp_values` CHECK (`version_no` > 0 AND `gwp_value` > 0 AND
        `usage_nature` IN ('DEVELOPMENT_REFERENCE','FORMAL') AND
        `status` IN ('ACTIVE','DISABLED') AND
        (`effective_to` IS NULL OR `effective_to` > `effective_from`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='温室气体GWP不可覆盖版本';

CREATE TABLE `biz_carbon_rounding_policy_version` (
    `rounding_policy_version_id` VARCHAR(32) NOT NULL,
    `policy_code` VARCHAR(64) NOT NULL,
    `version_no` INT NOT NULL,
    `calculation_context` VARCHAR(32) NOT NULL,
    `total_scale` INT NOT NULL,
    `intensity_scale` INT NOT NULL,
    `share_scale` INT NOT NULL,
    `rounding_mode` VARCHAR(20) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`rounding_policy_version_id`),
    UNIQUE KEY `uk_carbon_rounding_policy_version` (`policy_code`,`version_no`),
    CONSTRAINT `chk_carbon_rounding_policy` CHECK (`version_no` > 0 AND
        `calculation_context`='DECIMAL128' AND `total_scale` BETWEEN 0 AND 18 AND
        `intensity_scale` BETWEEN 0 AND 18 AND `share_scale` BETWEEN 0 AND 18 AND
        `rounding_mode`='HALF_UP' AND `status` IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='碳结果舍入策略版本';

CREATE TABLE `biz_carbon_factor` (
    `factor_id` VARCHAR(32) NOT NULL,
    `factor_code` VARCHAR(64) NOT NULL,
    `scope_type` VARCHAR(16) NOT NULL,
    `energy_item_code` VARCHAR(64) NOT NULL,
    `factor_category` VARCHAR(40) NOT NULL,
    `result_basis` VARCHAR(32) NOT NULL,
    `gas_code` VARCHAR(32) NOT NULL,
    `gas_coverage` VARCHAR(100) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`factor_id`),
    UNIQUE KEY `uk_carbon_factor_code` (`factor_code`),
    CONSTRAINT `chk_carbon_factor_scope` CHECK (`scope_type` IN ('SCOPE_1','SCOPE_2')),
    CONSTRAINT `chk_carbon_factor_category` CHECK (`factor_category` IN
        ('STATIONARY_COMBUSTION','PURCHASED_ELECTRICITY_LOCATION','PURCHASED_HEAT')),
    CONSTRAINT `chk_carbon_factor_basis` CHECK (`result_basis` IN ('GAS_MASS','CO2E_DIRECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排放因子稳定身份';

CREATE TABLE `biz_carbon_factor_version` (
    `factor_version_id` VARCHAR(32) NOT NULL,
    `factor_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `source_version_id` VARCHAR(32) NOT NULL,
    `applicability_level` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NULL,
    `region_code` VARCHAR(64) NULL,
    `input_unit_code` VARCHAR(64) NOT NULL,
    `standard_condition_code` VARCHAR(100) NULL,
    `usage_nature` VARCHAR(32) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `effective_from` DATETIME(3) NOT NULL,
    `effective_to` DATETIME(3) NULL,
    `formula_version_id` VARCHAR(32) NOT NULL,
    `rounding_policy_version_id` VARCHAR(32) NOT NULL,
    `config_revision` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `reviewed_by` BIGINT NULL,
    `reviewed_at` DATETIME(3) NULL,
    `review_comment` VARCHAR(500) NULL,
    `activated_by` BIGINT NULL,
    `activated_at` DATETIME(3) NULL,
    `disabled_by` BIGINT NULL,
    `disabled_at` DATETIME(3) NULL,
    PRIMARY KEY (`factor_version_id`),
    UNIQUE KEY `uk_carbon_factor_version` (`factor_id`,`version_no`),
    KEY `idx_carbon_factor_match` (`factor_id`,`status`,`effective_from`,`effective_to`),
    KEY `idx_carbon_factor_region_match` (`applicability_level`,`building_id`,`region_code`,`status`),
    CONSTRAINT `fk_carbon_factor_version_identity` FOREIGN KEY (`factor_id`)
        REFERENCES `biz_carbon_factor` (`factor_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_factor_version_source` FOREIGN KEY (`source_version_id`)
        REFERENCES `biz_carbon_factor_source_version` (`source_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_factor_version_formula` FOREIGN KEY (`formula_version_id`)
        REFERENCES `biz_carbon_formula_version` (`formula_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_factor_version_rounding` FOREIGN KEY (`rounding_policy_version_id`)
        REFERENCES `biz_carbon_rounding_policy_version` (`rounding_policy_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_factor_version_values` CHECK (`version_no` > 0 AND `config_revision` >= 0 AND
        `applicability_level` IN ('BUILDING_SPECIFIC','PROVINCE','NATIONAL','NOT_REGION_SPECIFIC') AND
        `usage_nature` IN ('DEVELOPMENT_REFERENCE','FORMAL') AND
        `status` IN ('PENDING_REVIEW','APPROVED','ACTIVE','DISABLED','REJECTED') AND
        (`effective_to` IS NULL OR `effective_to` > `effective_from`)),
    CONSTRAINT `chk_carbon_factor_version_building` CHECK
        ((`applicability_level`='BUILDING_SPECIFIC' AND `building_id` IS NOT NULL) OR
         (`applicability_level`<>'BUILDING_SPECIFIC' AND `building_id` IS NULL)),
    CONSTRAINT `chk_carbon_factor_version_region` CHECK
        ((`applicability_level`='PROVINCE' AND `region_code` IS NOT NULL) OR
         (`applicability_level`<>'PROVINCE' AND `region_code` IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可覆盖完整排放因子组合版本';

CREATE TABLE `biz_carbon_factor_component` (
    `component_id` VARCHAR(32) NOT NULL,
    `factor_version_id` VARCHAR(32) NOT NULL,
    `component_type` VARCHAR(40) NOT NULL,
    `component_value` DECIMAL(38,18) NOT NULL,
    `component_unit` VARCHAR(100) NOT NULL,
    `source_version_id` VARCHAR(32) NOT NULL,
    `evidence_reference` VARCHAR(1000) NOT NULL,
    PRIMARY KEY (`component_id`),
    UNIQUE KEY `uk_carbon_factor_component_type` (`factor_version_id`,`component_type`),
    CONSTRAINT `fk_carbon_component_factor_version` FOREIGN KEY (`factor_version_id`)
        REFERENCES `biz_carbon_factor_version` (`factor_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_component_source_version` FOREIGN KEY (`source_version_id`)
        REFERENCES `biz_carbon_factor_source_version` (`source_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_factor_component_type` CHECK (`component_type` IN
        ('LOWER_HEATING_VALUE','CARBON_CONTENT_PER_HEAT','OXIDATION_RATE','DIRECT_EMISSION_FACTOR')),
    CONSTRAINT `chk_carbon_factor_component_value` CHECK (`component_value` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排放因子组合不可变参数';

CREATE TABLE `biz_carbon_denominator` (
    `denominator_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `denominator_type` VARCHAR(32) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`denominator_id`),
    UNIQUE KEY `uk_carbon_denominator_identity` (`building_id`,`denominator_type`),
    CONSTRAINT `fk_carbon_denominator_building` FOREIGN KEY (`building_id`)
        REFERENCES `building` (`building_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_denominator_type` CHECK (`denominator_type` IN
        ('BUILDING_AREA','RESIDENT_POPULATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建筑碳强度分母稳定身份';

CREATE TABLE `biz_carbon_denominator_version` (
    `denominator_version_id` VARCHAR(32) NOT NULL,
    `denominator_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `denominator_value` DECIMAL(38,18) NOT NULL,
    `unit_code` VARCHAR(32) NOT NULL,
    `source_reference` VARCHAR(1000) NOT NULL,
    `evidence_reference` VARCHAR(1000) NOT NULL,
    `usage_nature` VARCHAR(32) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `effective_from` DATE NOT NULL,
    `effective_to` DATE NULL,
    `config_revision` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `reviewed_by` BIGINT NULL,
    `reviewed_at` DATETIME(3) NULL,
    `review_comment` VARCHAR(500) NULL,
    `activated_by` BIGINT NULL,
    `activated_at` DATETIME(3) NULL,
    `disabled_by` BIGINT NULL,
    `disabled_at` DATETIME(3) NULL,
    PRIMARY KEY (`denominator_version_id`),
    UNIQUE KEY `uk_carbon_denominator_version` (`denominator_id`,`version_no`),
    KEY `idx_carbon_denominator_match` (`denominator_id`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_carbon_denominator_version_identity` FOREIGN KEY (`denominator_id`)
        REFERENCES `biz_carbon_denominator` (`denominator_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_denominator_version_values` CHECK (`version_no` > 0 AND
        `denominator_value` > 0 AND `unit_code` IN ('M2','PERSON') AND `config_revision` >= 0 AND
        `usage_nature` IN ('DEVELOPMENT_REFERENCE','FORMAL') AND
        `status` IN ('PENDING_REVIEW','APPROVED','ACTIVE','DISABLED','REJECTED') AND
        (`effective_to` IS NULL OR `effective_to` > `effective_from`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建筑碳强度分母不可覆盖版本';

CREATE TABLE `biz_carbon_calculation_batch` (
    `calculation_batch_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `period_type` VARCHAR(16) NOT NULL,
    `period_start` DATETIME(3) NOT NULL,
    `period_end` DATETIME(3) NOT NULL,
    `timezone_id` VARCHAR(64) NOT NULL,
    `result_nature` VARCHAR(32) NOT NULL,
    `publication_status` VARCHAR(20) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `idempotency_key` VARCHAR(100) NOT NULL,
    `request_hash` VARCHAR(64) NOT NULL,
    `active_lock_key` VARCHAR(200) NULL,
    `rounding_policy_version_id` VARCHAR(32) NOT NULL,
    `supersedes_calculation_batch_id` VARCHAR(32) NULL,
    `started_at` DATETIME(3) NOT NULL,
    `deadline_at` DATETIME(3) NOT NULL,
    `completed_at` DATETIME(3) NULL,
    `duration_ms` BIGINT NULL,
    `snapshot_count` INT NOT NULL DEFAULT 0,
    `detail_count` INT NOT NULL DEFAULT 0,
    `slow_calculation` TINYINT(1) NOT NULL DEFAULT 0,
    `safe_error_code` VARCHAR(100) NULL,
    `safe_error_message` VARCHAR(500) NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`calculation_batch_id`),
    UNIQUE KEY `uk_carbon_calculation_idempotency` (`building_id`,`idempotency_key`),
    UNIQUE KEY `uk_carbon_calculation_active_lock` (`active_lock_key`),
    KEY `idx_carbon_calculation_query` (`building_id`,`period_type`,`period_start`,`status`),
    CONSTRAINT `fk_carbon_calculation_building` FOREIGN KEY (`building_id`)
        REFERENCES `building` (`building_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_calculation_rounding` FOREIGN KEY (`rounding_policy_version_id`)
        REFERENCES `biz_carbon_rounding_policy_version` (`rounding_policy_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_calculation_supersedes` FOREIGN KEY (`supersedes_calculation_batch_id`)
        REFERENCES `biz_carbon_calculation_batch` (`calculation_batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_calculation_values` CHECK (`period_type` IN ('MONTH','QUARTER','YEAR') AND
        `period_end` > `period_start` AND `result_nature` IN ('DEVELOPMENT_SIMULATION','FORMAL') AND
        `publication_status` IN ('DIRECT','CANDIDATE','PUBLISHED','SUPERSEDED') AND
        `status` IN ('CALCULATING','COMPLETED_COMPLETE','COMPLETED_INCOMPLETE','FAILED','FAILED_TIMEOUT') AND
        `snapshot_count` >= 0 AND `detail_count` >= 0 AND `slow_calculation` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='碳核算请求和执行批次';

CREATE TABLE `biz_carbon_calculation_item` (
    `calculation_item_id` VARCHAR(32) NOT NULL,
    `calculation_batch_id` VARCHAR(32) NOT NULL,
    `activity_snapshot_id` VARCHAR(32) NOT NULL,
    `activity_evidence_hash` VARCHAR(64) NOT NULL,
    `activity_period_start` DATETIME(3) NOT NULL,
    `activity_period_end` DATETIME(3) NOT NULL,
    `energy_item_code` VARCHAR(64) NOT NULL,
    `scope_type` VARCHAR(16) NOT NULL,
    `activity_quantity` DECIMAL(38,18) NOT NULL,
    `activity_unit_code` VARCHAR(64) NOT NULL,
    `factor_version_id` VARCHAR(32) NOT NULL,
    `formula_version_id` VARCHAR(32) NOT NULL,
    `gwp_version_id` VARCHAR(32) NULL,
    `raw_emission_kg_co2e` DECIMAL(38,18) NOT NULL,
    `final_emission_kg_co2e` DECIMAL(38,18) NOT NULL,
    `match_reason` VARCHAR(500) NOT NULL,
    `evidence_json` JSON NOT NULL,
    `evidence_hash` VARCHAR(64) NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`calculation_item_id`),
    UNIQUE KEY `uk_carbon_calculation_item_snapshot` (`calculation_batch_id`,`activity_snapshot_id`,`energy_item_code`),
    CONSTRAINT `fk_carbon_item_batch` FOREIGN KEY (`calculation_batch_id`)
        REFERENCES `biz_carbon_calculation_batch` (`calculation_batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_item_factor_version` FOREIGN KEY (`factor_version_id`)
        REFERENCES `biz_carbon_factor_version` (`factor_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_item_formula_version` FOREIGN KEY (`formula_version_id`)
        REFERENCES `biz_carbon_formula_version` (`formula_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_item_gwp_version` FOREIGN KEY (`gwp_version_id`)
        REFERENCES `biz_carbon_gwp_version` (`gwp_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_item_values` CHECK (`activity_period_end` > `activity_period_start` AND
        `scope_type` IN ('SCOPE_1','SCOPE_2') AND `activity_quantity` >= 0 AND
        `raw_emission_kg_co2e` >= 0 AND `final_emission_kg_co2e` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动段与固定因子证据的碳核算明细';

CREATE TABLE `biz_carbon_calculation_summary` (
    `summary_id` VARCHAR(32) NOT NULL,
    `calculation_batch_id` VARCHAR(32) NOT NULL,
    `metric_code` VARCHAR(100) NOT NULL,
    `dimension_code` VARCHAR(100) NOT NULL,
    `raw_value` DECIMAL(38,18) NULL,
    `final_value` DECIMAL(38,18) NULL,
    `unit_code` VARCHAR(32) NOT NULL,
    `denominator_version_id` VARCHAR(32) NULL,
    `unavailable_reason` VARCHAR(500) NULL,
    `evidence_hash` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`summary_id`),
    UNIQUE KEY `uk_carbon_summary_metric` (`calculation_batch_id`,`metric_code`,`dimension_code`),
    CONSTRAINT `fk_carbon_summary_batch` FOREIGN KEY (`calculation_batch_id`)
        REFERENCES `biz_carbon_calculation_batch` (`calculation_batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_summary_denominator` FOREIGN KEY (`denominator_version_id`)
        REFERENCES `biz_carbon_denominator_version` (`denominator_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_summary_value` CHECK
        ((`final_value` IS NOT NULL AND `unavailable_reason` IS NULL) OR
         (`final_value` IS NULL AND `unavailable_reason` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='范围能源品种总量占比和年度强度';

CREATE TABLE `biz_carbon_calculation_failure` (
    `failure_id` VARCHAR(32) NOT NULL,
    `calculation_batch_id` VARCHAR(32) NOT NULL,
    `activity_snapshot_id` VARCHAR(32) NOT NULL,
    `energy_item_code` VARCHAR(64) NOT NULL,
    `activity_period_start` DATETIME(3) NOT NULL,
    `activity_period_end` DATETIME(3) NOT NULL,
    `safe_error_code` VARCHAR(100) NOT NULL,
    `safe_error_message` VARCHAR(500) NOT NULL,
    `activity_evidence_hash` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`failure_id`),
    UNIQUE KEY `uk_carbon_calculation_failure_segment`
        (`calculation_batch_id`,`activity_snapshot_id`,`energy_item_code`),
    CONSTRAINT `fk_carbon_calculation_failure_batch` FOREIGN KEY (`calculation_batch_id`)
        REFERENCES `biz_carbon_calculation_batch` (`calculation_batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_calculation_failure_period` CHECK
        (`activity_period_end` > `activity_period_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='研发不完整计算中被安全拒绝的活动段';

CREATE TABLE `biz_carbon_result_relation` (
    `relation_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `old_calculation_batch_id` VARCHAR(32) NOT NULL,
    `new_calculation_batch_id` VARCHAR(32) NOT NULL,
    `relation_status` VARCHAR(20) NOT NULL,
    `approved_recalculation_batch_id` VARCHAR(32) NULL,
    `published_at` DATETIME(3) NULL,
    PRIMARY KEY (`relation_id`),
    UNIQUE KEY `uk_carbon_result_replacement` (`old_calculation_batch_id`,`new_calculation_batch_id`),
    CONSTRAINT `fk_carbon_result_relation_building` FOREIGN KEY (`building_id`)
        REFERENCES `building` (`building_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_result_relation_old` FOREIGN KEY (`old_calculation_batch_id`)
        REFERENCES `biz_carbon_calculation_batch` (`calculation_batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_result_relation_new` FOREIGN KEY (`new_calculation_batch_id`)
        REFERENCES `biz_carbon_calculation_batch` (`calculation_batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_result_relation_status` CHECK (`relation_status` IN
        ('CANDIDATE','CURRENT_FORMAL','SUPERSEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='碳结果候选发布和新旧替代关系';

CREATE TABLE `biz_carbon_dependency_change` (
    `change_id` VARCHAR(32) NOT NULL,
    `change_type` VARCHAR(40) NOT NULL,
    `source_object_type` VARCHAR(64) NOT NULL,
    `source_object_id` VARCHAR(64) NOT NULL,
    `change_detail` VARCHAR(500) NOT NULL,
    `old_version_id` VARCHAR(64) NULL,
    `new_version_id` VARCHAR(64) NOT NULL,
    `change_fingerprint` VARCHAR(64) NOT NULL,
    `building_id` VARCHAR(32) NULL,
    `organization_boundary` VARCHAR(100) NULL,
    `effective_from` DATETIME(3) NOT NULL,
    `effective_to` DATETIME(3) NULL,
    `triggered_by` BIGINT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `processed_at` DATETIME(3) NULL,
    PRIMARY KEY (`change_id`),
    UNIQUE KEY `uk_carbon_dependency_change_fingerprint` (`change_fingerprint`),
    KEY `idx_carbon_dependency_change_pending` (`status`,`created_at`),
    CONSTRAINT `chk_carbon_dependency_change_type` CHECK (`change_type` IN
        ('ACTIVITY_SNAPSHOT','FACTOR','MISSING_FACTOR_FILLED','FORMULA','GWP','DENOMINATOR','MANUAL')),
    CONSTRAINT `chk_carbon_dependency_change_status` CHECK (`status` IN
        ('PENDING','ANALYZING','PROCESSED','FAILED')),
    CONSTRAINT `chk_carbon_dependency_change_range` CHECK
        (`effective_to` IS NULL OR `effective_to` > `effective_from`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='与依赖业务变更同事务保存的碳重算来源';

CREATE TABLE `biz_carbon_recalculation_trigger` (
    `trigger_id` VARCHAR(32) NOT NULL,
    `change_id` VARCHAR(32) NOT NULL,
    `trigger_fingerprint` VARCHAR(64) NOT NULL,
    `trigger_reason` VARCHAR(40) NOT NULL,
    `correction_window_start` DATETIME(3) NULL,
    `correction_window_end` DATETIME(3) NULL,
    `impact_status` VARCHAR(20) NOT NULL,
    `impacted_item_count` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `analyzed_at` DATETIME(3) NULL,
    PRIMARY KEY (`trigger_id`),
    UNIQUE KEY `uk_carbon_recalc_trigger_fingerprint` (`trigger_fingerprint`),
    CONSTRAINT `fk_carbon_recalc_trigger_change` FOREIGN KEY (`change_id`)
        REFERENCES `biz_carbon_dependency_change` (`change_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_recalc_trigger_values` CHECK (`impact_status` IN
        ('PENDING','NO_IMPACT','IMPACTED','FAILED') AND `impacted_item_count` >= 0 AND
        ((`correction_window_start` IS NULL AND `correction_window_end` IS NULL) OR
         (`correction_window_start` IS NOT NULL AND `correction_window_end` > `correction_window_start`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='去重后的碳自动重算影响触发';

CREATE TABLE `biz_carbon_recalculation_batch` (
    `recalculation_batch_id` VARCHAR(32) NOT NULL,
    `batch_key` VARCHAR(64) NOT NULL,
    `trigger_reason` VARCHAR(40) NOT NULL,
    `organization_boundary` VARCHAR(100) NOT NULL,
    `result_nature` VARCHAR(32) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `scope_frozen` TINYINT(1) NOT NULL DEFAULT 0,
    `item_count` INT NOT NULL DEFAULT 0,
    `eligible_item_count` INT NOT NULL DEFAULT 0,
    `lease_token` VARCHAR(64) NULL,
    `lease_until` DATETIME(3) NULL,
    `started_at` DATETIME(3) NULL,
    `completed_at` DATETIME(3) NULL,
    `initiated_by` BIGINT NULL,
    `approved_by` BIGINT NULL,
    `approved_at` DATETIME(3) NULL,
    `review_comment` VARCHAR(500) NULL,
    `safe_error_code` VARCHAR(100) NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`recalculation_batch_id`),
    UNIQUE KEY `uk_carbon_recalc_batch_key` (`batch_key`),
    KEY `idx_carbon_recalc_batch_claim` (`status`,`lease_until`,`created_at`),
    CONSTRAINT `chk_carbon_recalc_batch_values` CHECK (`result_nature` IN
        ('DEVELOPMENT_SIMULATION','FORMAL') AND `status` IN
        ('PENDING_CALCULATION','CALCULATING','FAILED_RETRYABLE','PENDING_APPROVAL',
         'PUBLISHING','COMPLETED','DEAD','REJECTED') AND `scope_frozen` IN (0,1) AND
        `item_count` >= 0 AND `eligible_item_count` >= 0 AND `eligible_item_count` <= `item_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动重算调度和批次审批容器';

CREATE TABLE `biz_carbon_recalculation_batch_trigger` (
    `recalculation_batch_id` VARCHAR(32) NOT NULL,
    `trigger_id` VARCHAR(32) NOT NULL,
    PRIMARY KEY (`recalculation_batch_id`,`trigger_id`),
    CONSTRAINT `fk_carbon_recalc_batch_trigger_batch` FOREIGN KEY (`recalculation_batch_id`)
        REFERENCES `biz_carbon_recalculation_batch` (`recalculation_batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_recalc_batch_trigger_source` FOREIGN KEY (`trigger_id`)
        REFERENCES `biz_carbon_recalculation_trigger` (`trigger_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='重算批次归并的来源触发';

CREATE TABLE `biz_carbon_recalculation_item` (
    `recalculation_item_id` VARCHAR(32) NOT NULL,
    `recalculation_batch_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `accounting_year` INT NOT NULL,
    `old_calculation_batch_id` VARCHAR(32) NULL,
    `candidate_calculation_batch_id` VARCHAR(32) NULL,
    `status` VARCHAR(32) NOT NULL,
    `approval_eligible` TINYINT(1) NOT NULL DEFAULT 0,
    `retry_count` INT NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME(3) NULL,
    `safe_error_code` VARCHAR(100) NULL,
    `safe_error_message` VARCHAR(500) NULL,
    `active_lock_key` VARCHAR(100) NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `completed_at` DATETIME(3) NULL,
    PRIMARY KEY (`recalculation_item_id`),
    UNIQUE KEY `uk_carbon_recalc_item_year` (`recalculation_batch_id`,`building_id`,`accounting_year`),
    UNIQUE KEY `uk_carbon_recalc_item_active_lock` (`active_lock_key`),
    KEY `idx_carbon_recalc_item_execution` (`status`,`next_attempt_at`),
    CONSTRAINT `fk_carbon_recalc_item_batch` FOREIGN KEY (`recalculation_batch_id`)
        REFERENCES `biz_carbon_recalculation_batch` (`recalculation_batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_recalc_item_building` FOREIGN KEY (`building_id`)
        REFERENCES `building` (`building_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_recalc_item_old_result` FOREIGN KEY (`old_calculation_batch_id`)
        REFERENCES `biz_carbon_calculation_batch` (`calculation_batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_carbon_recalc_item_candidate` FOREIGN KEY (`candidate_calculation_batch_id`)
        REFERENCES `biz_carbon_calculation_batch` (`calculation_batch_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_carbon_recalc_item_values` CHECK (`accounting_year` BETWEEN 2000 AND 2200 AND
        `status` IN ('PENDING','CALCULATING','SUCCEEDED','FAILED_RETRYABLE','DEAD','PUBLISHED','REJECTED') AND
        `approval_eligible` IN (0,1) AND `retry_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建筑和核算年度最小自动重算计算项';

INSERT INTO `biz_carbon_formula_version`
(`formula_version_id`,`formula_code`,`version_no`,`algorithm_code`,`result_basis`,`gas_coverage`,
 `usage_nature`,`status`,`effective_from`) VALUES
('CFV_STATIONARY_CO2_V1','STATIONARY_COMBUSTION_CO2',1,'STATIONARY_COMBUSTION_CO2_V1',
 'GAS_MASS','CO2_ONLY_FOR_STATIONARY_COMBUSTION','FORMAL','ACTIVE','2000-01-01'),
('CFV_ELECTRICITY_CO2E_V1','PURCHASED_ELECTRICITY_LOCATION_CO2E',1,
 'PURCHASED_ELECTRICITY_LOCATION_CO2E_V1','CO2E_DIRECT','DIRECT_FACTOR_GAS_COVERAGE',
 'FORMAL','ACTIVE','2000-01-01'),
('CFV_HEAT_CO2E_V1','PURCHASED_HEAT_CO2E',1,'PURCHASED_HEAT_CO2E_V1',
 'CO2E_DIRECT','DIRECT_FACTOR_GAS_COVERAGE','FORMAL','ACTIVE','2000-01-01');

INSERT INTO `biz_carbon_gwp_version`
(`gwp_version_id`,`gwp_code`,`version_no`,`gas_code`,`gwp_value`,`source_reference`,
 `usage_nature`,`status`,`effective_from`) VALUES
('CGWP_CO2_V1','CO2_GWP',1,'CO2',1,'已确认首版固定燃烧CO2到CO2e定义值',
 'FORMAL','ACTIVE','2000-01-01');

INSERT INTO `biz_carbon_rounding_policy_version`
(`rounding_policy_version_id`,`policy_code`,`version_no`,`calculation_context`,`total_scale`,
 `intensity_scale`,`share_scale`,`rounding_mode`,`status`) VALUES
('CRP_DECIMAL128_V1','CARBON_RESULT_ROUNDING',1,'DECIMAL128',6,6,12,'HALF_UP','ACTIVE');

INSERT INTO `sys_backend_duty`
(`duty_key`,`duty_name`,`description`,`status`,`risk_level`,`version`) VALUES
('CARBON_RULE_MAINTAIN','碳规则维护','创建排放因子来源、因子组合和建筑分母版本','ENABLED','HIGH',1),
('CARBON_RULE_REVIEW','碳规则审核','审核排放因子组合和建筑分母版本','ENABLED','CRITICAL',1),
('CARBON_RULE_ACTIVATE','碳规则激活','激活或停用已审核碳规则版本','ENABLED','CRITICAL',1),
('CARBON_CALCULATION_RUN','碳核算执行','运行单建筑单周期有界碳核算','ENABLED','HIGH',1),
('CARBON_RECALCULATION_TRIGGER','碳重算发起','人工创建受权限约束的历史碳重算触发','ENABLED','HIGH',1),
('CARBON_RECALCULATION_APPROVE','碳重算审批','审批并发布完整权限范围内的正式候选结果批次','ENABLED','CRITICAL',1)
ON DUPLICATE KEY UPDATE
`duty_name`=VALUES(`duty_name`),`description`=VALUES(`description`),
`status`=VALUES(`status`),`risk_level`=VALUES(`risk_level`),`version`=VALUES(`version`);
