-- 第七闭环第一实现切片：能源品种、单位量纲、兼容矩阵和版本化测点绑定。
-- 历史 Excel 只发布待专业确认的研发基线，迁移不发布兼容规则，也不产生正式计算结果。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

CREATE TABLE `biz_energy_item` (
    `item_id` VARCHAR(32) NOT NULL,
    `item_code` VARCHAR(64) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`item_id`),
    UNIQUE KEY `uk_energy_item_code` (`item_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能源品种稳定身份';

CREATE TABLE `biz_energy_item_version` (
    `version_id` VARCHAR(32) NOT NULL,
    `item_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `item_name` VARCHAR(100) NOT NULL,
    `compatible_category` VARCHAR(32) NOT NULL,
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
    UNIQUE KEY `uk_energy_item_version_no` (`item_id`,`version_no`),
    KEY `idx_energy_item_version_effective` (`item_id`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_energy_item_version_item` FOREIGN KEY (`item_id`)
        REFERENCES `biz_energy_item` (`item_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_item_category` CHECK (`compatible_category` IN
        ('ELECTRICITY','NATURAL_GAS','HEAT','COLD','FUEL')),
    CONSTRAINT `chk_energy_item_status` CHECK (`status` IN
        ('DRAFT','PENDING_EXPERT','APPROVED','DISABLED')),
    CONSTRAINT `chk_energy_item_source` CHECK (`source_type` IN ('STANDARD','EXCEL','MANUAL')),
    CONSTRAINT `chk_energy_item_version_positive` CHECK (`version_no` > 0 AND `config_revision` >= 0),
    CONSTRAINT `chk_energy_item_effective_range` CHECK
        (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_energy_item_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能源品种不可覆盖版本';

CREATE TABLE `biz_energy_item_version_scope` (
    `version_id` VARCHAR(32) NOT NULL,
    `usage_scope` VARCHAR(40) NOT NULL,
    PRIMARY KEY (`version_id`,`usage_scope`),
    CONSTRAINT `fk_energy_item_scope_version` FOREIGN KEY (`version_id`)
        REFERENCES `biz_energy_item_version` (`version_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_item_usage_scope` CHECK (`usage_scope` IN
        ('STATIONARY_COMBUSTION','MOBILE_COMBUSTION','PURCHASED_ELECTRICITY','PURCHASED_HEAT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能源品种版本适用范围';

CREATE TABLE `biz_measurement_unit` (
    `unit_id` VARCHAR(32) NOT NULL,
    `unit_code` VARCHAR(64) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`unit_id`),
    UNIQUE KEY `uk_measurement_unit_code` (`unit_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计量单位稳定身份';

CREATE TABLE `biz_measurement_unit_version` (
    `version_id` VARCHAR(32) NOT NULL,
    `unit_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `symbol` VARCHAR(32) NOT NULL,
    `unit_name` VARCHAR(100) NOT NULL,
    `dimension_code` VARCHAR(40) NOT NULL,
    `canonical_unit_code` VARCHAR(64) NOT NULL,
    `scale_factor` DECIMAL(24,12) NOT NULL,
    `conversion_type` VARCHAR(32) NOT NULL,
    `standard_condition_code` VARCHAR(100) NULL,
    `decimal_precision` INT NOT NULL,
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
    UNIQUE KEY `uk_measurement_unit_version_no` (`unit_id`,`version_no`),
    KEY `idx_measurement_unit_symbol_effective` (`symbol`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_measurement_unit_version_unit` FOREIGN KEY (`unit_id`)
        REFERENCES `biz_measurement_unit` (`unit_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_measurement_unit_dimension` CHECK (`dimension_code` IN
        ('POWER','ENERGY','ACTUAL_VOLUME','NORMAL_VOLUME','MASS','STANDARD_COAL_EQUIVALENT')),
    CONSTRAINT `chk_measurement_unit_conversion` CHECK (`conversion_type` IN
        ('IDENTITY','FIXED_SCALE','REQUIRES_BUSINESS_RULE')),
    CONSTRAINT `chk_measurement_unit_status` CHECK (`status` IN
        ('DRAFT','PENDING_EXPERT','APPROVED','DISABLED')),
    CONSTRAINT `chk_measurement_unit_source` CHECK (`source_type` IN ('STANDARD','EXCEL','MANUAL')),
    CONSTRAINT `chk_measurement_unit_values` CHECK
        (`version_no` > 0 AND `scale_factor` > 0 AND `decimal_precision` BETWEEN 0 AND 12
         AND `config_revision` >= 0),
    CONSTRAINT `chk_measurement_unit_effective_range` CHECK
        (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_measurement_unit_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单位量纲不可覆盖版本';

CREATE TABLE `biz_energy_item_unit_compatibility` (
    `compatibility_id` VARCHAR(32) NOT NULL,
    `item_id` VARCHAR(32) NOT NULL,
    `unit_id` VARCHAR(32) NOT NULL,
    `value_semantics` VARCHAR(32) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`compatibility_id`),
    UNIQUE KEY `uk_energy_item_unit_semantics` (`item_id`,`unit_id`,`value_semantics`),
    CONSTRAINT `fk_energy_compatibility_item` FOREIGN KEY (`item_id`)
        REFERENCES `biz_energy_item` (`item_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_energy_compatibility_unit` FOREIGN KEY (`unit_id`)
        REFERENCES `biz_measurement_unit` (`unit_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_compatibility_semantics` CHECK (`value_semantics` IN
        ('INSTANTANEOUS','CUMULATIVE','PERIOD_TOTAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能源品种单位语义兼容身份';

CREATE TABLE `biz_energy_item_unit_compatibility_version` (
    `version_id` VARCHAR(32) NOT NULL,
    `compatibility_id` VARCHAR(32) NOT NULL,
    `version_no` INT NOT NULL,
    `allowed` TINYINT(1) NOT NULL,
    `conversion_requirement` VARCHAR(32) NOT NULL,
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
    UNIQUE KEY `uk_energy_compatibility_version_no` (`compatibility_id`,`version_no`),
    KEY `idx_energy_compatibility_effective`
        (`compatibility_id`,`status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_energy_compatibility_version` FOREIGN KEY (`compatibility_id`)
        REFERENCES `biz_energy_item_unit_compatibility` (`compatibility_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_compatibility_allowed` CHECK (`allowed` IN (0,1)),
    CONSTRAINT `chk_energy_compatibility_requirement` CHECK (`conversion_requirement` IN
        ('NONE','TIME_INTEGRATION','STANDARD_CONDITION','BUSINESS_RULE')),
    CONSTRAINT `chk_energy_compatibility_version_status` CHECK (`status` IN
        ('DRAFT','PENDING_EXPERT','APPROVED','DISABLED')),
    CONSTRAINT `chk_energy_compatibility_source` CHECK (`source_type` IN ('STANDARD','EXCEL','MANUAL')),
    CONSTRAINT `chk_energy_compatibility_version_values` CHECK
        (`version_no` > 0 AND `config_revision` >= 0),
    CONSTRAINT `chk_energy_compatibility_effective_range` CHECK
        (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_energy_compatibility_approval` CHECK
        (`status` <> 'APPROVED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能源品种单位语义兼容版本';

CREATE TABLE `biz_energy_point_item_binding` (
    `binding_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`binding_id`),
    UNIQUE KEY `uk_energy_point_item_binding_point` (`point_id`),
    CONSTRAINT `fk_energy_point_binding_point` FOREIGN KEY (`point_id`,`building_id`)
        REFERENCES `biz_data_point` (`point_id`,`building_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_energy_point_binding_building` FOREIGN KEY (`building_id`)
        REFERENCES `building` (`building_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测点能源品种绑定稳定身份';

CREATE TABLE `biz_energy_point_item_binding_version` (
    `binding_version_id` VARCHAR(32) NOT NULL,
    `binding_id` VARCHAR(32) NOT NULL,
    `binding_version` INT NOT NULL,
    `energy_item_version_id` VARCHAR(32) NOT NULL,
    `effective_from` DATETIME(3) NOT NULL,
    `effective_to` DATETIME(3) NULL,
    `confirmation_status` VARCHAR(20) NOT NULL,
    `evidence_reference` VARCHAR(500) NOT NULL,
    `config_revision` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `approved_by` BIGINT NULL,
    `approved_at` DATETIME(3) NULL,
    PRIMARY KEY (`binding_version_id`),
    UNIQUE KEY `uk_energy_point_binding_version_no` (`binding_id`,`binding_version`),
    KEY `idx_energy_point_binding_effective`
        (`binding_id`,`confirmation_status`,`effective_from`,`effective_to`),
    CONSTRAINT `fk_energy_point_binding_version_identity` FOREIGN KEY (`binding_id`)
        REFERENCES `biz_energy_point_item_binding` (`binding_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_energy_point_binding_item_version` FOREIGN KEY (`energy_item_version_id`)
        REFERENCES `biz_energy_item_version` (`version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_energy_point_binding_status` CHECK (`confirmation_status` IN
        ('PENDING_EXPERT','CONFIRMED','DISABLED')),
    CONSTRAINT `chk_energy_point_binding_values` CHECK
        (`binding_version` > 0 AND `config_revision` >= 0),
    CONSTRAINT `chk_energy_point_binding_effective_range` CHECK
        (`effective_to` IS NULL OR `effective_to` > `effective_from`),
    CONSTRAINT `chk_energy_point_binding_approval` CHECK
        (`confirmation_status` <> 'CONFIRMED' OR (`approved_by` IS NOT NULL AND `approved_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测点能源品种版本化有效绑定';

INSERT INTO `sys_backend_duty`
    (`duty_key`,`duty_name`,`description`,`status`,`risk_level`,`version`)
VALUES
    ('ENERGY_CATALOG_MAINTAIN','能源字典维护','创建待专业确认的能源品种、单位、兼容和绑定版本','ENABLED','HIGH',1),
    ('ENERGY_CATALOG_REVIEW','能源字典审核','审核能源品种、单位、兼容和测点绑定版本','ENABLED','CRITICAL',1)
ON DUPLICATE KEY UPDATE
    `duty_name`=VALUES(`duty_name`),`description`=VALUES(`description`),
    `status`=VALUES(`status`),`risk_level`=VALUES(`risk_level`),`version`=VALUES(`version`);

INSERT INTO `biz_energy_item` (`item_id`,`item_code`,`created_by`) VALUES
('EI_BITUMINOUS_COAL','BITUMINOUS_COAL',0),('EI_ANTHRACITE','ANTHRACITE',0),
('EI_LIGNITE','LIGNITE',0),('EI_DIESEL','DIESEL',0),('EI_GASOLINE','GASOLINE',0),
('EI_FUEL_OIL','FUEL_OIL',0),('EI_KEROSENE','KEROSENE',0),('EI_LPG','LPG',0),
('EI_LNG','LNG',0),('EI_NATURAL_GAS','NATURAL_GAS',0),
('EI_COKE_OVEN_GAS','COKE_OVEN_GAS',0),('EI_ELECTRICITY','ELECTRICITY',0),
('EI_HEAT','HEAT',0);

INSERT INTO `biz_energy_item_version`
(`version_id`,`item_id`,`version_no`,`item_name`,`compatible_category`,`status`,`source_type`,
 `source_reference`,`effective_from`,`config_revision`,`created_by`) VALUES
('EIV_BITUMINOUS_COAL_1','EI_BITUMINOUS_COAL',1,'烟煤','FUEL','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_ANTHRACITE_1','EI_ANTHRACITE',1,'无烟煤','FUEL','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_LIGNITE_1','EI_LIGNITE',1,'褐煤','FUEL','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_DIESEL_1','EI_DIESEL',1,'柴油','FUEL','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_GASOLINE_1','EI_GASOLINE',1,'汽油','FUEL','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_FUEL_OIL_1','EI_FUEL_OIL',1,'燃料油','FUEL','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_KEROSENE_1','EI_KEROSENE',1,'一般煤油','FUEL','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_LPG_1','EI_LPG',1,'液化石油气','FUEL','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_LNG_1','EI_LNG',1,'液化天然气','FUEL','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_NATURAL_GAS_1','EI_NATURAL_GAS',1,'天然气','NATURAL_GAS','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_COKE_OVEN_GAS_1','EI_COKE_OVEN_GAS',1,'焦炉煤气','FUEL','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_ELECTRICITY_1','EI_ELECTRICITY',1,'电力','ELECTRICITY','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_HEAT_1','EI_HEAT',1,'外购热力','HEAT','PENDING_EXPERT','EXCEL','历史碳盘查Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0);

INSERT INTO `biz_energy_item_version_scope` (`version_id`,`usage_scope`) VALUES
('EIV_BITUMINOUS_COAL_1','STATIONARY_COMBUSTION'),('EIV_ANTHRACITE_1','STATIONARY_COMBUSTION'),
('EIV_LIGNITE_1','STATIONARY_COMBUSTION'),('EIV_DIESEL_1','STATIONARY_COMBUSTION'),
('EIV_GASOLINE_1','STATIONARY_COMBUSTION'),('EIV_FUEL_OIL_1','STATIONARY_COMBUSTION'),
('EIV_KEROSENE_1','STATIONARY_COMBUSTION'),('EIV_LPG_1','STATIONARY_COMBUSTION'),
('EIV_LNG_1','STATIONARY_COMBUSTION'),('EIV_NATURAL_GAS_1','STATIONARY_COMBUSTION'),
('EIV_COKE_OVEN_GAS_1','STATIONARY_COMBUSTION'),
('EIV_ELECTRICITY_1','PURCHASED_ELECTRICITY'),('EIV_HEAT_1','PURCHASED_HEAT');

INSERT INTO `biz_measurement_unit` (`unit_id`,`unit_code`,`created_by`) VALUES
('EU_KW','KW',0),('EU_KWH','KWH',0),('EU_MWH','MWH',0),('EU_MJ','MJ',0),('EU_GJ','GJ',0),
('EU_M3','M3',0),('EU_NM3','NM3',0),('EU_TEN_THOUSAND_NM3','TEN_THOUSAND_NM3',0),
('EU_KG','KG',0),('EU_T','T',0),('EU_KGCE','KGCE',0),('EU_TCE','TCE',0);

INSERT INTO `biz_measurement_unit_version`
(`version_id`,`unit_id`,`version_no`,`symbol`,`unit_name`,`dimension_code`,`canonical_unit_code`,
 `scale_factor`,`conversion_type`,`standard_condition_code`,`decimal_precision`,`status`,`source_type`,
 `source_reference`,`effective_from`,`config_revision`,`created_by`) VALUES
('EUV_KW_1','EU_KW',1,'kW','千瓦','POWER','KW',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_KWH_1','EU_KWH',1,'kWh','千瓦时','ENERGY','KWH',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_MWH_1','EU_MWH',1,'MWh','兆瓦时','ENERGY','KWH',1000,'FIXED_SCALE',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_MJ_1','EU_MJ',1,'MJ','兆焦','ENERGY','MJ',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_GJ_1','EU_GJ',1,'GJ','吉焦','ENERGY','MJ',1000,'FIXED_SCALE',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_M3_1','EU_M3',1,'m³','立方米','ACTUAL_VOLUME','M3',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_NM3_1','EU_NM3',1,'Nm³','标准立方米','NORMAL_VOLUME','NM3',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，标准状态仍待确认','2000-01-01',0,0),
('EUV_TEN_THOUSAND_NM3_1','EU_TEN_THOUSAND_NM3',1,'万Nm³','万标准立方米','NORMAL_VOLUME','NM3',10000,'FIXED_SCALE',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，标准状态仍待确认','2000-01-01',0,0),
('EUV_KG_1','EU_KG',1,'kg','千克','MASS','KG',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_T_1','EU_T',1,'t','吨','MASS','KG',1000,'FIXED_SCALE',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_KGCE_1','EU_KGCE',1,'kgce','千克标准煤','STANDARD_COAL_EQUIVALENT','KGCE',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，仅计算输出','2000-01-01',0,0),
('EUV_TCE_1','EU_TCE',1,'tce','吨标准煤','STANDARD_COAL_EQUIVALENT','KGCE',1000,'FIXED_SCALE',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，仅计算输出','2000-01-01',0,0);
