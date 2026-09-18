-- 外机三相电表 339 的已确认产品、十一测点和可继续发布的协议草稿；不创建实际设备或部署目标。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

INSERT INTO `biz_device_product`
(`product_id`, `product_code`, `product_name`, `manufacturer`, `model`,
 `equipment_type_code`, `expected_profile_code`, `identity_type`, `status`)
VALUES ('PRODUCT_ODU_METER_339', 'OUTDOOR_UNIT_METER_339', '空调外机三相电表11测点模板',
        NULL, '339', 'ODU', 'OUTDOOR_UNIT_METER_339', 'SN', 'ENABLED');

-- 设备上报值已经计入 PT/CT 倍率；协议映射统一保持 scale=1、offset=0。
INSERT INTO `biz_product_point_template`
(`template_point_id`, `product_id`, `metric_code`, `point_name_template`, `suffix_code`,
 `unit`, `min_value`, `max_value`, `for_calc`, `required_flag`, `sort_order`, `status`)
VALUES
('TPL_ODU_339_UA',  'PRODUCT_ODU_METER_339', 'VOLTAGE_A',       '外机A相电压',       'UA',  'V',   NULL, NULL, 0, 1, 10, 1),
('TPL_ODU_339_UB',  'PRODUCT_ODU_METER_339', 'VOLTAGE_B',       '外机B相电压',       'UB',  'V',   NULL, NULL, 0, 1, 20, 1),
('TPL_ODU_339_UC',  'PRODUCT_ODU_METER_339', 'VOLTAGE_C',       '外机C相电压',       'UC',  'V',   NULL, NULL, 0, 1, 30, 1),
('TPL_ODU_339_IA',  'PRODUCT_ODU_METER_339', 'CURRENT_A',       '外机A相电流',       'IA',  'A',   NULL, NULL, 0, 1, 40, 1),
('TPL_ODU_339_IB',  'PRODUCT_ODU_METER_339', 'CURRENT_B',       '外机B相电流',       'IB',  'A',   NULL, NULL, 0, 1, 50, 1),
('TPL_ODU_339_IC',  'PRODUCT_ODU_METER_339', 'CURRENT_C',       '外机C相电流',       'IC',  'A',   NULL, NULL, 0, 1, 60, 1),
('TPL_ODU_339_P',   'PRODUCT_ODU_METER_339', 'POWER',           '外机总有功输入功率', 'P',   'kW',  NULL, NULL, 0, 1, 70, 1),
('TPL_ODU_339_PF',  'PRODUCT_ODU_METER_339', 'POWER_FACTOR',    '外机总功率因数',     'Pf',  '1',   NULL, NULL, 0, 1, 80, 1),
('TPL_ODU_339_F',   'PRODUCT_ODU_METER_339', 'FREQUENCY',       '电源频率',           'F',   'Hz',  NULL, NULL, 0, 1, 90, 1),
('TPL_ODU_339_EPP', 'PRODUCT_ODU_METER_339', 'POSITIVE_ENERGY', '正向有功累计电能',   'EPP', 'kWh', NULL, NULL, 0, 1, 100, 1),
('TPL_ODU_339_EPN', 'PRODUCT_ODU_METER_339', 'NEGATIVE_ENERGY', '反向有功累计电能',   'EPN', 'kWh', NULL, NULL, 0, 1, 110, 1);

INSERT INTO `biz_protocol_draft`
(`draft_id`, `revision`, `configuration_json`, `updated_at`, `updated_by`)
VALUES
('PROTOCOL_ODU_METER_339_V1', 1,
 '{"name":"外机三相电表339解析配置","productId":"PRODUCT_ODU_METER_339","profileCode":"OUTDOOR_UNIT_METER_339","sourceTopic":"device/raw/energy/up","identityType":"SN","identityPath":"/SN","discriminatorPath":"/param/ID1/M","discriminatorValue":"339","timestampPath":null,"mappings":[{"sourcePath":"/param/ID1/A1/U","metricCode":"VOLTAGE_A","sourceUnit":"V","targetUnit":"V","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":10},{"sourcePath":"/param/ID1/B1/U","metricCode":"VOLTAGE_B","sourceUnit":"V","targetUnit":"V","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":20},{"sourcePath":"/param/ID1/C1/U","metricCode":"VOLTAGE_C","sourceUnit":"V","targetUnit":"V","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":30},{"sourcePath":"/param/ID1/A1/I","metricCode":"CURRENT_A","sourceUnit":"A","targetUnit":"A","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":40},{"sourcePath":"/param/ID1/B1/I","metricCode":"CURRENT_B","sourceUnit":"A","targetUnit":"A","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":50},{"sourcePath":"/param/ID1/C1/I","metricCode":"CURRENT_C","sourceUnit":"A","targetUnit":"A","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":60},{"sourcePath":"/param/ID1/T1/P","metricCode":"POWER","sourceUnit":"kW","targetUnit":"kW","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":70},{"sourcePath":"/param/ID1/T1/Pf","metricCode":"POWER_FACTOR","sourceUnit":"1","targetUnit":"1","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":80},{"sourcePath":"/param/ID1/T1/F","metricCode":"FREQUENCY","sourceUnit":"Hz","targetUnit":"Hz","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":90},{"sourcePath":"/param/ID1/T1/EPP","metricCode":"POSITIVE_ENERGY","sourceUnit":"kWh","targetUnit":"kWh","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":100},{"sourcePath":"/param/ID1/T1/EPN","metricCode":"NEGATIVE_ENERGY","sourceUnit":"kWh","targetUnit":"kWh","scale":"1","offset":"0","required":true,"enabled":true,"sortOrder":110}]}',
 1789392343097, 1);
