-- 仅用于填报和正式参数API契约的隔离H2结构；MySQL检查约束另由专用MySQL验收。

CREATE TABLE IF NOT EXISTS `biz_carbon_factor_source` (
    `source_id` VARCHAR(32) NOT NULL,
    `source_code` VARCHAR(64) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`source_id`),
    UNIQUE KEY `uk_carbon_factor_source_code` (`source_code`)
);

CREATE TABLE IF NOT EXISTS `biz_carbon_factor_source_version` (
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
);

CREATE TABLE IF NOT EXISTS `biz_carbon_gwp_version` (
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
);

CREATE TABLE IF NOT EXISTS `biz_carbon_factor` (
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
);

CREATE TABLE IF NOT EXISTS `biz_carbon_factor_version` (
    data_year INT NULL, accounting_year INT NULL,
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
        `applicability_level` IN ('BUILDING_SPECIFIC','PROVINCE','GRID_REGION','NATIONAL','NOT_REGION_SPECIFIC') AND
        `usage_nature` IN ('DEVELOPMENT_REFERENCE','FORMAL') AND
        `status` IN ('PENDING_REVIEW','APPROVED','ACTIVE','DISABLED','REJECTED') AND
        (`effective_to` IS NULL OR `effective_to` > `effective_from`)),
    CONSTRAINT `chk_carbon_factor_version_building` CHECK
        ((`applicability_level`='BUILDING_SPECIFIC' AND `building_id` IS NOT NULL) OR
         (`applicability_level`<>'BUILDING_SPECIFIC' AND `building_id` IS NULL)),
    CONSTRAINT `chk_carbon_factor_version_region` CHECK
        ((`applicability_level`='PROVINCE' AND `region_code` IS NOT NULL) OR
         (`applicability_level`<>'PROVINCE' AND `region_code` IS NULL))
);

CREATE TABLE IF NOT EXISTS `biz_carbon_factor_component` (
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
);

CREATE TABLE IF NOT EXISTS `biz_carbon_denominator` (
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
);

CREATE TABLE IF NOT EXISTS `biz_carbon_denominator_version` (
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
);

CREATE TABLE IF NOT EXISTS `biz_carbon_dependency_change` (
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
);

INSERT INTO biz_carbon_gwp_version(gwp_version_id,gwp_code,version_no,gas_code,source_reference,gwp_value,usage_nature,status,effective_from) VALUES ('CGWP_CO2_V1','CO2',1,'CO2','CO2定义值',1,'FORMAL','ACTIVE','2000-01-01') ON DUPLICATE KEY UPDATE gwp_version_id=VALUES(gwp_version_id);

INSERT INTO biz_carbon_rounding_policy_version(rounding_policy_version_id,policy_code,version_no,calculation_context,total_scale,intensity_scale,share_scale,rounding_mode,status) VALUES ('CRP_DECIMAL128_V1','CARBON_RESULT_ROUNDING',1,'DECIMAL128',6,6,12,'HALF_UP','ACTIVE') ON DUPLICATE KEY UPDATE rounding_policy_version_id=VALUES(rounding_policy_version_id);
