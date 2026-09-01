CREATE TABLE `biz_standard_coal_lhv` (
    `lhv_id` VARCHAR(32) NOT NULL,
    `lhv_code` VARCHAR(64) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`lhv_id`),
    UNIQUE KEY `uk_standard_coal_lhv_code` (`lhv_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准煤低位热值稳定身份';

CREATE TABLE `biz_standard_coal_lhv_version` (
    `version_id` VARCHAR(32) NOT NULL,
    `lhv_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `lhv_value` DECIMAL(30,12) NOT NULL,
    `energy_unit_version_id` VARCHAR(32) NOT NULL,
    `coal_unit_version_id` VARCHAR(32) NOT NULL,
    `parameter_unit` VARCHAR(32) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `source_type` VARCHAR(20) NOT NULL,
    `source_reference` VARCHAR(500) NOT NULL,
    `effective_from` DATETIME(3) NOT NULL,
    `effective_to` DATETIME(3) NULL,
    `config_revision` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `approved_by` BIGINT NULL,
    `approved_at` DATETIME(3) NULL,
    PRIMARY KEY (`version_id`),
    UNIQUE KEY `uk_standard_coal_lhv_version_no` (`lhv_id`,`version_no`),
    KEY `idx_standard_coal_lhv_effective` (`lhv_id`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_standard_coal_lhv_identity` FOREIGN KEY (`lhv_id`)
        REFERENCES `biz_standard_coal_lhv` (`lhv_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_standard_coal_lhv_energy_unit` FOREIGN KEY (`energy_unit_version_id`)
        REFERENCES `biz_measurement_unit_version` (`version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_standard_coal_lhv_coal_unit` FOREIGN KEY (`coal_unit_version_id`)
        REFERENCES `biz_measurement_unit_version` (`version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_standard_coal_lhv_values` CHECK
        (`version_no` > 0 AND `lhv_value` > 0 AND `config_revision` >= 0),
    CONSTRAINT `chk_standard_coal_lhv_unit` CHECK (`parameter_unit`='GJ_PER_TCE'),
    CONSTRAINT `chk_standard_coal_lhv_status` CHECK (`status` IN ('PENDING_EXPERT','APPROVED','DISABLED')),
    CONSTRAINT `chk_standard_coal_lhv_source` CHECK (`source_type` IN ('STANDARD','EXCEL','MANUAL')),
    CONSTRAINT `chk_standard_coal_lhv_range` CHECK (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_standard_coal_lhv_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准煤低位热值版本';

CREATE TABLE `biz_energy_conversion_formula` (
    `formula_id` VARCHAR(32) NOT NULL,
    `formula_code` VARCHAR(64) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`formula_id`),
    UNIQUE KEY `uk_energy_conversion_formula_code` (`formula_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='折标公式稳定身份';

CREATE TABLE `biz_energy_conversion_formula_version` (
    `version_id` VARCHAR(32) NOT NULL,
    `formula_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `method` VARCHAR(32) NOT NULL,
    `perspective` VARCHAR(32) NOT NULL,
    `algorithm_code` VARCHAR(64) NOT NULL,
    `applicable_input_unit_version_id` VARCHAR(32) NOT NULL,
    `result_unit_version_id` VARCHAR(32) NOT NULL,
    `parameter_unit` VARCHAR(32) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `source_type` VARCHAR(20) NOT NULL,
    `source_reference` VARCHAR(500) NOT NULL,
    `effective_from` DATETIME(3) NOT NULL,
    `effective_to` DATETIME(3) NULL,
    `config_revision` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `approved_by` BIGINT NULL,
    `approved_at` DATETIME(3) NULL,
    PRIMARY KEY (`version_id`),
    UNIQUE KEY `uk_energy_conversion_formula_version_no` (`formula_id`,`version_no`),
    KEY `idx_energy_conversion_formula_effective` (`formula_id`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_energy_conversion_formula_identity` FOREIGN KEY (`formula_id`)
        REFERENCES `biz_energy_conversion_formula` (`formula_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_energy_conversion_formula_input_unit` FOREIGN KEY (`applicable_input_unit_version_id`)
        REFERENCES `biz_measurement_unit_version` (`version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_energy_conversion_formula_result_unit` FOREIGN KEY (`result_unit_version_id`)
        REFERENCES `biz_measurement_unit_version` (`version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_conversion_formula_method` CHECK (`method` IN
        ('DIRECT_TCE_FACTOR','LOWER_HEATING_VALUE','ENERGY_EQUIVALENT')),
    CONSTRAINT `chk_energy_conversion_formula_perspective` CHECK (`perspective` IN
        ('CALORIFIC_EQUIVALENT','PRIMARY_EQUIVALENT')),
    CONSTRAINT `chk_energy_conversion_formula_algorithm` CHECK (`algorithm_code` IN
        ('DIRECT_TCE_FACTOR_V1','LOWER_HEATING_VALUE_V1','ENERGY_EQUIVALENT_V1')),
    CONSTRAINT `chk_energy_conversion_formula_parameter_unit` CHECK (`parameter_unit` IN
        ('TCE_PER_INPUT_UNIT','GJ_PER_INPUT_UNIT','MJ_PER_INPUT_UNIT')),
    CONSTRAINT `chk_energy_conversion_formula_values` CHECK (`version_no` > 0 AND `config_revision` >= 0),
    CONSTRAINT `chk_energy_conversion_formula_status` CHECK (`status` IN ('PENDING_EXPERT','APPROVED','DISABLED')),
    CONSTRAINT `chk_energy_conversion_formula_source` CHECK (`source_type` IN ('STANDARD','EXCEL','MANUAL')),
    CONSTRAINT `chk_energy_conversion_formula_range` CHECK (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_energy_conversion_formula_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='折标公式版本';

CREATE TABLE `biz_energy_conversion_parameter` (
    `parameter_id` VARCHAR(32) NOT NULL,
    `parameter_code` VARCHAR(64) NOT NULL,
    `energy_item_id` VARCHAR(32) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`parameter_id`),
    UNIQUE KEY `uk_energy_conversion_parameter_code` (`parameter_code`),
    CONSTRAINT `fk_energy_conversion_parameter_item` FOREIGN KEY (`energy_item_id`)
        REFERENCES `biz_energy_item` (`item_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='折标参数稳定身份';

CREATE TABLE `biz_energy_conversion_parameter_version` (
    `version_id` VARCHAR(32) NOT NULL,
    `parameter_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `energy_item_version_id` VARCHAR(32) NOT NULL,
    `formula_version_id` VARCHAR(32) NOT NULL,
    `parameter_value` DECIMAL(30,12) NOT NULL,
    `parameter_unit` VARCHAR(32) NOT NULL,
    `standard_coal_lhv_version_id` VARCHAR(32) NULL,
    `consumption_scope` VARCHAR(32) NOT NULL,
    `region_code` VARCHAR(32) NOT NULL,
    `usage_scope` VARCHAR(32) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `source_type` VARCHAR(20) NOT NULL,
    `source_reference` VARCHAR(500) NOT NULL,
    `effective_from` DATETIME(3) NOT NULL,
    `effective_to` DATETIME(3) NULL,
    `config_revision` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `approved_by` BIGINT NULL,
    `approved_at` DATETIME(3) NULL,
    PRIMARY KEY (`version_id`),
    UNIQUE KEY `uk_energy_conversion_parameter_version_no` (`parameter_id`,`version_no`),
    KEY `idx_energy_conversion_parameter_match`
        (`status`,`consumption_scope`,`region_code`,`usage_scope`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_energy_conversion_parameter_identity` FOREIGN KEY (`parameter_id`)
        REFERENCES `biz_energy_conversion_parameter` (`parameter_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_energy_conversion_parameter_item_version` FOREIGN KEY (`energy_item_version_id`)
        REFERENCES `biz_energy_item_version` (`version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_energy_conversion_parameter_formula_version` FOREIGN KEY (`formula_version_id`)
        REFERENCES `biz_energy_conversion_formula_version` (`version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_energy_conversion_parameter_lhv_version` FOREIGN KEY (`standard_coal_lhv_version_id`)
        REFERENCES `biz_standard_coal_lhv_version` (`version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_conversion_parameter_values` CHECK
        (`version_no` > 0 AND `parameter_value` > 0 AND `config_revision` >= 0),
    CONSTRAINT `chk_energy_conversion_parameter_unit` CHECK (`parameter_unit` IN
        ('TCE_PER_INPUT_UNIT','GJ_PER_INPUT_UNIT','MJ_PER_INPUT_UNIT')),
    CONSTRAINT `chk_energy_conversion_parameter_scope` CHECK (`consumption_scope` IN
        ('STATIONARY_COMBUSTION','PURCHASED_ELECTRICITY','PURCHASED_HEAT')),
    CONSTRAINT `chk_energy_conversion_parameter_usage` CHECK (`usage_scope` IN
        ('DEVELOPMENT_SIMULATION','PRODUCTION')),
    CONSTRAINT `chk_energy_conversion_parameter_status` CHECK (`status` IN
        ('PENDING_EXPERT','APPROVED','DISABLED')),
    CONSTRAINT `chk_energy_conversion_parameter_source` CHECK (`source_type` IN ('STANDARD','EXCEL','MANUAL')),
    CONSTRAINT `chk_energy_conversion_parameter_range` CHECK (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_energy_conversion_parameter_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='折标参数版本';

INSERT INTO `sys_backend_duty`
    (`duty_key`,`duty_name`,`description`,`status`,`risk_level`,`version`)
VALUES
    ('ENERGY_RULE_MAINTAIN','折标规则维护','创建待专业确认的标准煤、公式和折标参数版本','ENABLED','HIGH',1),
    ('ENERGY_RULE_REVIEW','折标规则审核','审核标准煤、公式和折标参数版本','ENABLED','CRITICAL',1),
    ('ENERGY_CALCULATION_RUN','能源折标计算','执行受建筑范围保护的折标计算','ENABLED','HIGH',1)
ON DUPLICATE KEY UPDATE
    `duty_name`=VALUES(`duty_name`),`description`=VALUES(`description`),
    `status`=VALUES(`status`),`risk_level`=VALUES(`risk_level`),`version`=VALUES(`version`);

INSERT INTO `biz_standard_coal_lhv` (`lhv_id`,`lhv_code`,`created_by`) VALUES
('SCL_STANDARD','STANDARD_COAL_LHV',0);

INSERT INTO `biz_standard_coal_lhv_version`
(`version_id`,`lhv_id`,`version_no`,`lhv_value`,`energy_unit_version_id`,`coal_unit_version_id`,
 `parameter_unit`,`status`,`source_type`,`source_reference`,`effective_from`,`config_revision`,`created_by`) VALUES
('SCLV_STANDARD_1','SCL_STANDARD',1,29.3076,'EUV_GJ_1','EUV_TCE_1','GJ_PER_TCE',
 'PENDING_EXPERT','STANDARD','历史标准折算截图与第七闭环设计§9.2，仅研发基线，待专业确认，生产不可用',
 '2000-01-01',0,0);

INSERT INTO `biz_energy_conversion_formula` (`formula_id`,`formula_code`,`created_by`) VALUES
('ECF_LHV_T','LHV_PER_T',0),
('ECF_LHV_10KNM3','LHV_PER_TEN_THOUSAND_NM3',0),
('ECF_ELECTRICITY_CAL','ELECTRICITY_CALORIFIC_EQUIVALENT',0),
('ECF_ELECTRICITY_PRIMARY','ELECTRICITY_PRIMARY_EQUIVALENT',0),
('ECF_HEAT_CAL','PURCHASED_HEAT_CALORIFIC_EQUIVALENT',0);

INSERT INTO `biz_energy_conversion_formula_version`
(`version_id`,`formula_id`,`version_no`,`method`,`perspective`,`algorithm_code`,
 `applicable_input_unit_version_id`,`result_unit_version_id`,`parameter_unit`,`status`,`source_type`,
 `source_reference`,`effective_from`,`config_revision`,`created_by`) VALUES
('ECFV_LHV_T_1','ECF_LHV_T',1,'LOWER_HEATING_VALUE','CALORIFIC_EQUIVALENT','LOWER_HEATING_VALUE_V1',
 'EUV_T_1','EUV_TCE_1','GJ_PER_INPUT_UNIT','PENDING_EXPERT','STANDARD','第七闭环设计§9.3，待专业确认','2000-01-01',0,0),
('ECFV_LHV_10KNM3_1','ECF_LHV_10KNM3',1,'LOWER_HEATING_VALUE','CALORIFIC_EQUIVALENT','LOWER_HEATING_VALUE_V1',
 'EUV_TEN_THOUSAND_NM3_1','EUV_TCE_1','GJ_PER_INPUT_UNIT','PENDING_EXPERT','STANDARD','第七闭环设计§9.3，待专业确认','2000-01-01',0,0),
('ECFV_ELECTRICITY_CAL_1','ECF_ELECTRICITY_CAL',1,'ENERGY_EQUIVALENT','CALORIFIC_EQUIVALENT','ENERGY_EQUIVALENT_V1',
 'EUV_KWH_1','EUV_TCE_1','MJ_PER_INPUT_UNIT','PENDING_EXPERT','STANDARD','第七闭环设计§9.5，待专业确认','2000-01-01',0,0),
('ECFV_ELECTRICITY_PRIMARY_1','ECF_ELECTRICITY_PRIMARY',1,'ENERGY_EQUIVALENT','PRIMARY_EQUIVALENT','ENERGY_EQUIVALENT_V1',
 'EUV_KWH_1','EUV_TCE_1','MJ_PER_INPUT_UNIT','PENDING_EXPERT','STANDARD','第七闭环设计§9.5，等价值系数待专业确认','2000-01-01',0,0),
('ECFV_HEAT_CAL_1','ECF_HEAT_CAL',1,'ENERGY_EQUIVALENT','CALORIFIC_EQUIVALENT','ENERGY_EQUIVALENT_V1',
 'EUV_GJ_1','EUV_TCE_1','MJ_PER_INPUT_UNIT','PENDING_EXPERT','STANDARD','第七闭环设计§9.4，待专业确认','2000-01-01',0,0);

INSERT INTO `biz_energy_conversion_parameter`
(`parameter_id`,`parameter_code`,`energy_item_id`,`created_by`) VALUES
('ECP_BITUMINOUS_LHV','BITUMINOUS_COAL_LHV','EI_BITUMINOUS_COAL',0),
('ECP_ANTHRACITE_LHV','ANTHRACITE_LHV','EI_ANTHRACITE',0),
('ECP_LIGNITE_LHV','LIGNITE_LHV','EI_LIGNITE',0),
('ECP_DIESEL_LHV','DIESEL_LHV','EI_DIESEL',0),
('ECP_GASOLINE_LHV','GASOLINE_LHV','EI_GASOLINE',0),
('ECP_FUEL_OIL_LHV','FUEL_OIL_LHV','EI_FUEL_OIL',0),
('ECP_KEROSENE_LHV','KEROSENE_LHV','EI_KEROSENE',0),
('ECP_LPG_LHV','LPG_LHV','EI_LPG',0),
('ECP_LNG_LHV','LNG_LHV','EI_LNG',0),
('ECP_NATURAL_GAS_LHV','NATURAL_GAS_LHV','EI_NATURAL_GAS',0),
('ECP_COKE_OVEN_GAS_LHV','COKE_OVEN_GAS_LHV','EI_COKE_OVEN_GAS',0),
('ECP_ELECTRICITY_CAL','ELECTRICITY_CALORIFIC_FACTOR','EI_ELECTRICITY',0),
('ECP_HEAT_CAL','PURCHASED_HEAT_CALORIFIC_FACTOR','EI_HEAT',0);

INSERT INTO `biz_energy_conversion_parameter_version`
(`version_id`,`parameter_id`,`version_no`,`energy_item_version_id`,`formula_version_id`,
 `parameter_value`,`parameter_unit`,`standard_coal_lhv_version_id`,`consumption_scope`,`region_code`,
 `usage_scope`,`status`,`source_type`,`source_reference`,`effective_from`,`config_revision`,`created_by`) VALUES
('ECPV_BITUMINOUS_LHV_1','ECP_BITUMINOUS_LHV',1,'EIV_BITUMINOUS_COAL_1','ECFV_LHV_T_1',22.400,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_ANTHRACITE_LHV_1','ECP_ANTHRACITE_LHV',1,'EIV_ANTHRACITE_1','ECFV_LHV_T_1',23.200,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_LIGNITE_LHV_1','ECP_LIGNITE_LHV',1,'EIV_LIGNITE_1','ECFV_LHV_T_1',14.100,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_DIESEL_LHV_1','ECP_DIESEL_LHV',1,'EIV_DIESEL_1','ECFV_LHV_T_1',43.330,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_GASOLINE_LHV_1','ECP_GASOLINE_LHV',1,'EIV_GASOLINE_1','ECFV_LHV_T_1',44.800,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_FUEL_OIL_LHV_1','ECP_FUEL_OIL_LHV',1,'EIV_FUEL_OIL_1','ECFV_LHV_T_1',40.190,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_KEROSENE_LHV_1','ECP_KEROSENE_LHV',1,'EIV_KEROSENE_1','ECFV_LHV_T_1',44.750,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_LPG_LHV_1','ECP_LPG_LHV',1,'EIV_LPG_1','ECFV_LHV_T_1',47.310,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_LNG_LHV_1','ECP_LNG_LHV',1,'EIV_LNG_1','ECFV_LHV_T_1',41.868,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_NATURAL_GAS_LHV_1','ECP_NATURAL_GAS_LHV',1,'EIV_NATURAL_GAS_1','ECFV_LHV_10KNM3_1',389.310,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，标准状态待确认，生产不可用','2000-01-01',0,0),
('ECPV_COKE_OVEN_GAS_LHV_1','ECP_COKE_OVEN_GAS_LHV',1,'EIV_COKE_OVEN_GAS_1','ECFV_LHV_10KNM3_1',173.500,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，标准状态待确认，生产不可用','2000-01-01',0,0),
('ECPV_ELECTRICITY_CAL_1','ECP_ELECTRICITY_CAL',1,'EIV_ELECTRICITY_1','ECFV_ELECTRICITY_CAL_1',3.6,'MJ_PER_INPUT_UNIT','SCLV_STANDARD_1','PURCHASED_ELECTRICITY','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','STANDARD','第七闭环设计§9.5，当量值研发基线，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_HEAT_CAL_1','ECP_HEAT_CAL',1,'EIV_HEAT_1','ECFV_HEAT_CAL_1',1000,'MJ_PER_INPUT_UNIT','SCLV_STANDARD_1','PURCHASED_HEAT','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','STANDARD','第七闭环设计§9.4，外购热力研发基线，待专业确认，生产不可用','2000-01-01',0,0);
