-- 在退役旧 HVAC 基线前，将可复用数据复制到活动库之外的独立归档库。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `iot_platform_archive`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`legacy_hvac_archive_manifest` (
    `archive_key` VARCHAR(64) NOT NULL,
    `archived_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `source_schema` VARCHAR(64) NOT NULL,
    `migration_version` VARCHAR(16) NOT NULL,
    `note` VARCHAR(500) NOT NULL,
    PRIMARY KEY (`archive_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_data_source` LIKE `iot_platform`.`biz_data_source`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_collection_policy` LIKE `iot_platform`.`biz_collection_policy`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_collection_policy_version` LIKE `iot_platform`.`biz_collection_policy_version`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_collection_review_request` LIKE `iot_platform`.`biz_collection_review_request`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_collection_config_audit_log` LIKE `iot_platform`.`biz_collection_config_audit_log`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_quality_usage_policy` LIKE `iot_platform`.`biz_quality_usage_policy`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_quality_usage_policy_version` LIKE `iot_platform`.`biz_quality_usage_policy_version`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_quality_usage_policy_level` LIKE `iot_platform`.`biz_quality_usage_policy_level`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_quality_usage_change_set` LIKE `iot_platform`.`biz_quality_usage_change_set`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_quality_usage_review_request` LIKE `iot_platform`.`biz_quality_usage_review_request`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_quality_usage_audit_log` LIKE `iot_platform`.`biz_quality_usage_audit_log`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_indicator` LIKE `iot_platform`.`biz_indicator`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_point_alias` LIKE `iot_platform`.`biz_point_alias`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_data_point` LIKE `iot_platform`.`biz_data_point`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_equipment` LIKE `iot_platform`.`biz_equipment`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_point_naming_rule` LIKE `iot_platform`.`biz_point_naming_rule`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_equipment_type` LIKE `iot_platform`.`biz_equipment_type`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_space` LIKE `iot_platform`.`biz_space`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`biz_system_group` LIKE `iot_platform`.`biz_system_group`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`sys_menu` LIKE `iot_platform`.`sys_menu`;
CREATE TABLE IF NOT EXISTS `iot_platform_archive`.`sys_role_menu` LIKE `iot_platform`.`sys_role_menu`;

REPLACE INTO `iot_platform_archive`.`biz_data_source`
SELECT * FROM `iot_platform`.`biz_data_source` WHERE `source_id` = 'SOURCE_MQTT_FREEZE_V1';
REPLACE INTO `iot_platform_archive`.`biz_collection_policy`
SELECT p.* FROM `iot_platform`.`biz_collection_policy` p
WHERE p.`alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$';
REPLACE INTO `iot_platform_archive`.`biz_collection_policy_version`
SELECT v.* FROM `iot_platform`.`biz_collection_policy_version` v
JOIN `iot_platform`.`biz_collection_policy` p ON p.`policy_id` = v.`policy_id`
WHERE p.`alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$';
REPLACE INTO `iot_platform_archive`.`biz_collection_review_request`
    (`request_id`,`building_id`,`target_type`,`target_id`,`target_config_revision`,`status`,
     `submitted_by`,`submitted_at`,`reviewer_id`,`review_comment`,`reviewed_at`,`withdrawn_at`,
     `create_time`,`update_time`)
SELECT r.`request_id`,r.`building_id`,r.`target_type`,r.`target_id`,r.`target_config_revision`,r.`status`,
       r.`submitted_by`,r.`submitted_at`,r.`reviewer_id`,r.`review_comment`,r.`reviewed_at`,r.`withdrawn_at`,
       r.`create_time`,r.`update_time`
FROM `iot_platform`.`biz_collection_review_request` r
WHERE (`target_type` = 'SOURCE_ACTIVATION' AND `target_id` = 'SOURCE_MQTT_FREEZE_V1')
   OR (`target_type` = 'ALIAS_ACTIVATION' AND `target_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$')
   OR (`target_type` = 'POLICY_VERSION' AND `target_id` IN (
       SELECT `version_id` FROM `iot_platform_archive`.`biz_collection_policy_version`));
REPLACE INTO `iot_platform_archive`.`biz_collection_config_audit_log`
SELECT a.* FROM `iot_platform`.`biz_collection_config_audit_log` a
WHERE (`object_type` = 'DATA_SOURCE' AND `object_id` = 'SOURCE_MQTT_FREEZE_V1')
   OR (`object_type` = 'POINT_ALIAS' AND `object_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$')
   OR `version_id` IN (SELECT `version_id` FROM `iot_platform_archive`.`biz_collection_policy_version`)
   OR `object_id` IN (SELECT `policy_id` FROM `iot_platform_archive`.`biz_collection_policy`)
   OR `object_id` IN (SELECT `version_id` FROM `iot_platform_archive`.`biz_collection_policy_version`);

REPLACE INTO `iot_platform_archive`.`biz_quality_usage_policy`
SELECT * FROM `iot_platform`.`biz_quality_usage_policy`
WHERE `point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$';
REPLACE INTO `iot_platform_archive`.`biz_quality_usage_policy_version`
SELECT v.* FROM `iot_platform`.`biz_quality_usage_policy_version` v
JOIN `iot_platform`.`biz_quality_usage_policy` p ON p.`policy_id` = v.`policy_id`
WHERE p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$';
REPLACE INTO `iot_platform_archive`.`biz_quality_usage_policy_level`
SELECT l.* FROM `iot_platform`.`biz_quality_usage_policy_level` l
JOIN `iot_platform_archive`.`biz_quality_usage_policy_version` v ON v.`version_id` = l.`version_id`;
REPLACE INTO `iot_platform_archive`.`biz_quality_usage_review_request`
    (`request_id`,`change_set_id`,`request_no`,`status`,`review_mode`,`submitted_revision`,
     `snapshot_json`,`snapshot_sha256`,`submitted_by`,`submitted_at`,`reviewer_id`,`review_comment`,
     `reviewed_at`,`withdrawn_by`,`withdrawn_at`,`idempotency_key`,`request_sha256`,`create_time`,`update_time`)
SELECT r.`request_id`,r.`change_set_id`,r.`request_no`,r.`status`,r.`review_mode`,r.`submitted_revision`,
       r.`snapshot_json`,r.`snapshot_sha256`,r.`submitted_by`,r.`submitted_at`,r.`reviewer_id`,r.`review_comment`,
       r.`reviewed_at`,r.`withdrawn_by`,r.`withdrawn_at`,r.`idempotency_key`,r.`request_sha256`,r.`create_time`,r.`update_time`
FROM `iot_platform`.`biz_quality_usage_review_request` r
WHERE EXISTS (
    SELECT 1 FROM JSON_TABLE(r.`snapshot_json`, '$[*]' COLUMNS (`point_id` VARCHAR(32) PATH '$.pointId')) j
    WHERE j.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
);
REPLACE INTO `iot_platform_archive`.`biz_quality_usage_change_set`
SELECT c.* FROM `iot_platform`.`biz_quality_usage_change_set` c
WHERE c.`change_set_id` IN (
    SELECT `change_set_id` FROM `iot_platform_archive`.`biz_quality_usage_review_request`
);
REPLACE INTO `iot_platform_archive`.`biz_quality_usage_audit_log`
SELECT a.* FROM `iot_platform`.`biz_quality_usage_audit_log` a
WHERE `object_id` IN (SELECT `policy_id` FROM `iot_platform_archive`.`biz_quality_usage_policy`)
   OR `object_id` IN (SELECT `version_id` FROM `iot_platform_archive`.`biz_quality_usage_policy_version`)
   OR `version_id` IN (SELECT `version_id` FROM `iot_platform_archive`.`biz_quality_usage_policy_version`)
   OR `object_id` IN (SELECT `change_set_id` FROM `iot_platform_archive`.`biz_quality_usage_change_set`)
   OR `object_id` IN (SELECT `request_id` FROM `iot_platform_archive`.`biz_quality_usage_review_request`)
   OR (`object_type` = 'QUALITY_USAGE_MIGRATION' AND `object_id` = 'QUALITY_USAGE_INITIAL_V1');

REPLACE INTO `iot_platform_archive`.`biz_indicator`
SELECT * FROM `iot_platform`.`biz_indicator`
WHERE `indicator_id` IN ('INDICATOR_WCR_COP_B1','INDICATOR_TOWER_EFF_B1','INDICATOR_PUMP_EFF_B1','INDICATOR_AHU_EFF_B1');
REPLACE INTO `iot_platform_archive`.`biz_point_alias`
SELECT * FROM `iot_platform`.`biz_point_alias` WHERE `alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$';
REPLACE INTO `iot_platform_archive`.`biz_data_point`
SELECT * FROM `iot_platform`.`biz_data_point` WHERE `point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$';
REPLACE INTO `iot_platform_archive`.`biz_equipment`
SELECT * FROM `iot_platform`.`biz_equipment`
WHERE `equip_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1');
REPLACE INTO `iot_platform_archive`.`biz_point_naming_rule`
SELECT * FROM `iot_platform`.`biz_point_naming_rule`
WHERE `rule_id` IN ('RULE_WCR_MAIN','RULE_WCR_PC','RULE_WCR_CT','RULE_WCR_PCD','RULE_AHU_MAIN','RULE_DBO_ENV','RULE_RHO_ENV');
REPLACE INTO `iot_platform_archive`.`biz_equipment_type`
SELECT * FROM `iot_platform`.`biz_equipment_type` WHERE `type_code` IN ('WCR','WCT','WCP','AHU','Bh','Bs');
REPLACE INTO `iot_platform_archive`.`biz_space`
SELECT * FROM `iot_platform`.`biz_space` WHERE `space_id` = 'SPACE001';
REPLACE INTO `iot_platform_archive`.`biz_system_group`
SELECT * FROM `iot_platform`.`biz_system_group` WHERE `system_group_id` = 'GROUP001';
REPLACE INTO `iot_platform_archive`.`sys_menu`
SELECT * FROM `iot_platform`.`sys_menu`
WHERE `id` IN (100,101,110,120,130,131,132,133,140,141,150,151,160,161,
               200,210,211,212,220,221,222,223,230,231,232,240,241,242,250,251,252,253,254,255,
               260,300,310,311,400,401,402,403,404,405);
REPLACE INTO `iot_platform_archive`.`sys_role_menu`
SELECT * FROM `iot_platform`.`sys_role_menu`
WHERE `menu_id` IN (100,101,110,120,130,131,132,133,140,141,150,151,160,161,
                    200,210,211,212,220,221,222,223,230,231,232,240,241,242,250,251,252,253,254,255,
                    260,300,310,311,400,401,402,403,404,405);

INSERT INTO `iot_platform_archive`.`legacy_hvac_archive_manifest`
    (`archive_key`,`source_schema`,`migration_version`,`note`)
VALUES ('V58_LEGACY_HVAC_BASELINE','iot_platform','V58',
        '旧 HVAC 设备、19 测点、别名、指标、治理策略、共享目录语义及菜单的迁移前快照')
ON DUPLICATE KEY UPDATE `archived_at` = CURRENT_TIMESTAMP(3), `note` = VALUES(`note`);
