-- 复用 V48 的 ODU 分类，仅补充电表接入命名规则，不预置协议、测点或真实设备。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

INSERT INTO `biz_point_naming_rule`
(`rule_id`, `standard_version`, `family_code`, `component_code`, `code_template`, `standard_source`, `status`)
VALUES ('RULE_ODU_MAIN', 'PLATFORM_DEVICE_CATALOG_V1', 'ODU', 'MAIN', 'ODU[n]', 'PLATFORM_DEVICE_CATALOG_V1', 1);
