-- 独立空调内机七测点产品模板；仅提供后续绑定所需元数据，不创建实际设备或身份。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

INSERT INTO `biz_equipment_type`
(`type_code`, `type_name`, `asset_code_prefix`, `equip_category`, `standard_source`, `status`)
VALUES ('IDU', '空调内机', 'IDU', 'INDOOR_UNIT', 'INDOOR_UNIT_METER_V1', 1);

INSERT INTO `biz_point_naming_rule`
(`rule_id`, `standard_version`, `family_code`, `component_code`, `code_template`, `standard_source`, `status`)
VALUES ('RULE_IDU_MAIN', 'INDOOR_UNIT_METER_V1', 'IDU', 'MAIN', 'IDU[n]', 'INDOOR_UNIT_METER_V1', 1);

INSERT INTO `biz_device_product`
(`product_id`, `product_code`, `product_name`, `manufacturer`, `model`,
 `equipment_type_code`, `expected_profile_code`, `identity_type`, `status`)
VALUES ('PRODUCT_IDU_METER_1039', 'INDOOR_UNIT_METER_1039', '空调内机七测点电参量模板',
        NULL, '1039', 'IDU', 'INDOOR_UNIT_METER_1039', 'SN', 'ENABLED');

-- 无设备方确认的专业上下限；七点仅用于原始值展示，不进入既有 HVAC 公式。
INSERT INTO `biz_product_point_template`
(`template_point_id`, `product_id`, `metric_code`, `point_name_template`, `suffix_code`,
 `unit`, `min_value`, `max_value`, `for_calc`, `required_flag`, `sort_order`, `status`)
VALUES
('TPL_IDU_1039_U',   'PRODUCT_IDU_METER_1039', 'VOLTAGE',                '内机电压',   'U',   'V',   NULL, NULL, 0, 1, 10, 1),
('TPL_IDU_1039_I',   'PRODUCT_IDU_METER_1039', 'CURRENT',                '内机电流',   'I',   'A',   NULL, NULL, 0, 1, 20, 1),
('TPL_IDU_1039_P',   'PRODUCT_IDU_METER_1039', 'POWER',                  '内机输入功率','P',   'kW',  NULL, NULL, 0, 1, 30, 1),
('TPL_IDU_1039_PF',  'PRODUCT_IDU_METER_1039', 'POWER_FACTOR',           '功率因数',   'Pf',  '1',   NULL, NULL, 0, 1, 40, 1),
('TPL_IDU_1039_F',   'PRODUCT_IDU_METER_1039', 'FREQUENCY',              '频率',       'F',   'Hz',  NULL, NULL, 0, 1, 50, 1),
('TPL_IDU_1039_EPP', 'PRODUCT_IDU_METER_1039', 'POSITIVE_ENERGY',        '正向电能',   'EPP', 'kWh', NULL, NULL, 0, 1, 60, 1),
('TPL_IDU_1039_EPN', 'PRODUCT_IDU_METER_1039', 'NEGATIVE_ENERGY',        '反向电能',   'EPN', 'kWh', NULL, NULL, 0, 1, 70, 1);
