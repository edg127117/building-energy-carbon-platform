CREATE TABLE IF NOT EXISTS `biz_carbon_formula_version` (
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
);

CREATE TABLE IF NOT EXISTS `biz_carbon_rounding_policy_version` (
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
);
