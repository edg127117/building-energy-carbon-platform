-- 归档验证通过后，从活动库退役旧 HVAC 基线并建立规范工作区菜单树。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

DROP PROCEDURE IF EXISTS `retire_legacy_hvac_and_govern_menus`;
DELIMITER //
CREATE PROCEDURE `retire_legacy_hvac_and_govern_menus`()
main: BEGIN
    DECLARE v_count INT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF NOT EXISTS (
        SELECT 1 FROM `iot_platform_archive`.`legacy_hvac_archive_manifest`
        WHERE `archive_key` = 'V58_LEGACY_HVAC_BASELINE'
    ) OR EXISTS (
        SELECT 1 FROM `biz_data_source` s
        LEFT JOIN `iot_platform_archive`.`biz_data_source` a ON a.`source_id` = s.`source_id`
        WHERE s.`source_id` = 'SOURCE_MQTT_FREEZE_V1' AND a.`source_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_collection_policy` s
        LEFT JOIN `iot_platform_archive`.`biz_collection_policy` a ON a.`policy_id` = s.`policy_id`
        WHERE s.`alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$' AND a.`policy_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_collection_policy_version` s
        JOIN `biz_collection_policy` p ON p.`policy_id` = s.`policy_id`
        LEFT JOIN `iot_platform_archive`.`biz_collection_policy_version` a ON a.`version_id` = s.`version_id`
        WHERE p.`alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$' AND a.`version_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_collection_review_request` s
        LEFT JOIN `iot_platform_archive`.`biz_collection_review_request` a ON a.`request_id` = s.`request_id`
        WHERE ((s.`target_type` = 'SOURCE_ACTIVATION' AND s.`target_id` = 'SOURCE_MQTT_FREEZE_V1')
            OR (s.`target_type` = 'ALIAS_ACTIVATION' AND s.`target_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$')
            OR (s.`target_type` = 'POLICY_VERSION' AND s.`target_id` IN (
                SELECT `version_id` FROM `iot_platform_archive`.`biz_collection_policy_version`)))
          AND a.`request_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_collection_config_audit_log` s
        LEFT JOIN `iot_platform_archive`.`biz_collection_config_audit_log` a ON a.`audit_id` = s.`audit_id`
        WHERE ((s.`object_type` = 'DATA_SOURCE' AND s.`object_id` = 'SOURCE_MQTT_FREEZE_V1')
            OR (s.`object_type` = 'POINT_ALIAS' AND s.`object_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$')
            OR s.`version_id` IN (SELECT `version_id` FROM `iot_platform_archive`.`biz_collection_policy_version`)
            OR s.`object_id` IN (SELECT `policy_id` FROM `iot_platform_archive`.`biz_collection_policy`)
            OR s.`object_id` IN (SELECT `version_id` FROM `iot_platform_archive`.`biz_collection_policy_version`))
          AND a.`audit_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_quality_usage_policy` s
        LEFT JOIN `iot_platform_archive`.`biz_quality_usage_policy` a ON a.`policy_id` = s.`policy_id`
        WHERE s.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$' AND a.`policy_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_quality_usage_policy_version` s
        JOIN `biz_quality_usage_policy` p ON p.`policy_id` = s.`policy_id`
        LEFT JOIN `iot_platform_archive`.`biz_quality_usage_policy_version` a ON a.`version_id` = s.`version_id`
        WHERE p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$' AND a.`version_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_quality_usage_policy_level` s
        JOIN `biz_quality_usage_policy_version` v ON v.`version_id` = s.`version_id`
        JOIN `biz_quality_usage_policy` p ON p.`policy_id` = v.`policy_id`
        LEFT JOIN `iot_platform_archive`.`biz_quality_usage_policy_level` a ON a.`policy_level_id` = s.`policy_level_id`
        WHERE p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$' AND a.`policy_level_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_quality_usage_review_request` s
        LEFT JOIN `iot_platform_archive`.`biz_quality_usage_review_request` a ON a.`request_id` = s.`request_id`
        WHERE EXISTS (
            SELECT 1 FROM JSON_TABLE(s.`snapshot_json`, '$[*]' COLUMNS (`point_id` VARCHAR(32) PATH '$.pointId')) j
            WHERE j.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
        ) AND a.`request_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_quality_usage_change_set` s
        JOIN `biz_quality_usage_review_request` r ON r.`change_set_id` = s.`change_set_id`
        JOIN `iot_platform_archive`.`biz_quality_usage_review_request` ar ON ar.`request_id` = r.`request_id`
        LEFT JOIN `iot_platform_archive`.`biz_quality_usage_change_set` a ON a.`change_set_id` = s.`change_set_id`
        WHERE a.`change_set_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_quality_usage_audit_log` s
        LEFT JOIN `iot_platform_archive`.`biz_quality_usage_audit_log` a ON a.`audit_id` = s.`audit_id`
        WHERE (s.`object_id` IN (SELECT `policy_id` FROM `iot_platform_archive`.`biz_quality_usage_policy`)
            OR s.`object_id` IN (SELECT `version_id` FROM `iot_platform_archive`.`biz_quality_usage_policy_version`)
            OR s.`version_id` IN (SELECT `version_id` FROM `iot_platform_archive`.`biz_quality_usage_policy_version`)
            OR s.`object_id` IN (SELECT `change_set_id` FROM `iot_platform_archive`.`biz_quality_usage_change_set`)
            OR s.`object_id` IN (SELECT `request_id` FROM `iot_platform_archive`.`biz_quality_usage_review_request`)
            OR (s.`object_type` = 'QUALITY_USAGE_MIGRATION' AND s.`object_id` = 'QUALITY_USAGE_INITIAL_V1'))
          AND a.`audit_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_indicator` s
        LEFT JOIN `iot_platform_archive`.`biz_indicator` a ON a.`indicator_id` = s.`indicator_id`
        WHERE s.`indicator_id` IN ('INDICATOR_WCR_COP_B1','INDICATOR_TOWER_EFF_B1','INDICATOR_PUMP_EFF_B1','INDICATOR_AHU_EFF_B1')
          AND a.`indicator_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_point_alias` s
        LEFT JOIN `iot_platform_archive`.`biz_point_alias` a ON a.`alias_id` = s.`alias_id`
        WHERE s.`alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$' AND a.`alias_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_data_point` s
        LEFT JOIN `iot_platform_archive`.`biz_data_point` a ON a.`point_id` = s.`point_id`
        WHERE s.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$' AND a.`point_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_equipment` s
        LEFT JOIN `iot_platform_archive`.`biz_equipment` a ON a.`equip_id` = s.`equip_id`
        WHERE s.`equip_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1')
          AND a.`equip_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_point_naming_rule` s
        LEFT JOIN `iot_platform_archive`.`biz_point_naming_rule` a ON a.`rule_id` = s.`rule_id`
        WHERE s.`rule_id` IN ('RULE_WCR_MAIN','RULE_WCR_PC','RULE_WCR_CT','RULE_WCR_PCD','RULE_AHU_MAIN','RULE_DBO_ENV','RULE_RHO_ENV')
          AND a.`rule_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_equipment_type` s
        LEFT JOIN `iot_platform_archive`.`biz_equipment_type` a ON a.`type_code` = s.`type_code`
        WHERE s.`type_code` IN ('WCR','WCT','WCP','AHU','Bh','Bs') AND a.`type_code` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_space` s
        LEFT JOIN `iot_platform_archive`.`biz_space` a ON a.`space_id` = s.`space_id`
        WHERE s.`space_id` = 'SPACE001' AND a.`space_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `biz_system_group` s
        LEFT JOIN `iot_platform_archive`.`biz_system_group` a ON a.`system_group_id` = s.`system_group_id`
        WHERE s.`system_group_id` = 'GROUP001' AND a.`system_group_id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `sys_menu` s
        LEFT JOIN `iot_platform_archive`.`sys_menu` a ON a.`id` = s.`id`
        WHERE s.`id` IN (100,101,110,120,130,131,132,133,140,141,150,151,160,161,
                         200,210,211,212,220,221,222,223,230,231,232,240,241,242,250,251,252,253,254,255,
                         260,300,310,311,400,401,402,403,404,405)
          AND a.`id` IS NULL
    ) OR EXISTS (
        SELECT 1 FROM `sys_role_menu` s
        LEFT JOIN `iot_platform_archive`.`sys_role_menu` a ON a.`id` = s.`id`
        WHERE s.`menu_id` IN (100,101,110,120,130,131,132,133,140,141,150,151,160,161,
                              200,210,211,212,220,221,222,223,230,231,232,240,241,242,250,251,252,253,254,255,
                              260,300,310,311,400,401,402,403,404,405)
          AND a.`id` IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEGACY_HVAC_ARCHIVE_INCOMPLETE';
    END IF;

    START TRANSACTION;

    CREATE TEMPORARY TABLE `tmp_legacy_hvac_roles` AS
    SELECT DISTINCT `role_id` FROM `sys_role_menu` WHERE `menu_id` = 101;

    IF EXISTS (
        SELECT 1 FROM `biz_data_source`
        WHERE (`source_code` = 'MQTT_STANDARD_V1' AND `source_id` <> 'SOURCE_MQTT_STANDARD_V1')
           OR (`source_id` = 'SOURCE_MQTT_STANDARD_V1' AND `source_code` <> 'MQTT_STANDARD_V1')
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'STANDARD_MQTT_SOURCE_IDENTITY_CONFLICT';
    END IF;

    INSERT INTO `biz_data_source`
        (`source_id`,`source_code`,`source_name`,`building_id`,`source_category`,`transport_type`,
         `status`,`description`,`config_revision`,`runtime_revision`,`create_by`,`update_by`)
    SELECT 'SOURCE_MQTT_STANDARD_V1','MQTT_STANDARD_V1','标准 MQTT 设备接入','BLD001',
           `source_category`,`transport_type`,`status`,'建筑能碳平台标准 MQTT 接入来源',
           `config_revision`,`runtime_revision`,`create_by`,`update_by`
    FROM `biz_data_source`
    WHERE `source_id` = 'SOURCE_MQTT_FREEZE_V1'
      AND NOT EXISTS (SELECT 1 FROM `biz_data_source` WHERE `source_id` = 'SOURCE_MQTT_STANDARD_V1');

    UPDATE `biz_collection_policy` p
    JOIN `biz_point_alias` a ON a.`alias_id` = p.`alias_id`
    SET p.`source_id` = 'SOURCE_MQTT_STANDARD_V1'
    WHERE p.`source_id` = 'SOURCE_MQTT_FREEZE_V1' AND a.`source_system` <> 'MQTT_FREEZE_V1';
    UPDATE `biz_point_alias`
    SET `source_id` = 'SOURCE_MQTT_STANDARD_V1'
    WHERE `source_id` = 'SOURCE_MQTT_FREEZE_V1' AND `source_system` <> 'MQTT_FREEZE_V1';

    IF EXISTS (
        SELECT 1 FROM `biz_point_alias`
        WHERE (`source_id` = 'SOURCE_MQTT_FREEZE_V1' OR `source_system` = 'MQTT_FREEZE_V1')
          AND `alias_id` NOT REGEXP '^ALIAS0(0[1-9]|1[0-9])$'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEGACY_HVAC_CUSTOM_ALIAS_REQUIRES_REVIEW';
    END IF;
    IF EXISTS (
        SELECT 1 FROM `biz_device_identity`
        WHERE `equip_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1')
    ) OR EXISTS (
        SELECT 1 FROM `biz_device_parameter_candidate`
        WHERE `equip_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1')
    ) OR EXISTS (
        SELECT 1 FROM `biz_device_parameter_conflict`
        WHERE `equip_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1')
    ) OR EXISTS (
        SELECT 1 FROM `biz_device_parameter_set`
        WHERE `equip_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1')
    ) OR EXISTS (
        SELECT 1 FROM `biz_device_parameter_recalc_job`
        WHERE `equip_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1')
    ) OR EXISTS (
        SELECT 1 FROM `biz_device_parameter_legacy_staging`
        WHERE `equip_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1')
    ) OR EXISTS (
        SELECT 1 FROM `biz_asset_assignment_version_item`
        WHERE `equipment_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1')
           OR (`object_type` = 'EQUIPMENT'
               AND `object_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1'))
           OR (`object_type` = 'POINT' AND `object_id` REGEXP '^POINT0(0[1-9]|1[0-9])$')
    ) OR EXISTS (
        SELECT 1 FROM `biz_device_product`
        WHERE `equipment_type_code` IN ('WCR','WCT','WCP','AHU','Bh','Bs')
    ) OR EXISTS (
        SELECT 1 FROM `biz_device_parameter_applicability`
        WHERE `equipment_type_code` IN ('WCR','WCT','WCP','AHU','Bh','Bs')
    ) OR EXISTS (
        SELECT 1 FROM `biz_device_parameter_legacy_mapping`
        WHERE `equipment_type_code` IN ('WCR','WCT','WCP','AHU','Bh','Bs')
    ) OR EXISTS (
        SELECT 1 FROM `biz_energy_point_profile` WHERE `point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
    ) OR EXISTS (
        SELECT 1 FROM `biz_energy_point_item_binding` WHERE `point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
    ) OR EXISTS (
        SELECT 1 FROM `biz_energy_meter_event` WHERE `meter_point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
    ) OR EXISTS (
        SELECT 1 FROM `biz_energy_activity_correction` WHERE `meter_point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
    ) OR EXISTS (
        SELECT 1 FROM `biz_energy_integration_policy` WHERE `meter_point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEGACY_HVAC_BUSINESS_REFERENCE_REQUIRES_REVIEW';
    END IF;

    CREATE TEMPORARY TABLE `tmp_legacy_collection_policy` AS
    SELECT `policy_id` FROM `biz_collection_policy` WHERE `alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$';
    CREATE TEMPORARY TABLE `tmp_legacy_collection_version` AS
    SELECT v.`version_id` FROM `biz_collection_policy_version` v
    JOIN `tmp_legacy_collection_policy` p ON p.`policy_id` = v.`policy_id`;
    IF EXISTS (
        SELECT 1 FROM `biz_collection_policy_version` v
        JOIN `biz_collection_policy` p ON p.`policy_id` = v.`policy_id`
        WHERE p.`alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$'
          AND (v.`version_no` <> 1 OR v.`change_source` <> 'INITIAL_MIGRATION')
    ) OR EXISTS (
        SELECT 1 FROM `biz_collection_policy_version` v
        JOIN `biz_collection_policy` p ON p.`policy_id` = v.`policy_id`
        WHERE p.`alias_id` NOT REGEXP '^ALIAS0(0[1-9]|1[0-9])$'
          AND EXISTS (
              SELECT 1 FROM `biz_collection_policy_version` legacy_v
              JOIN `biz_collection_policy` legacy_p ON legacy_p.`policy_id` = legacy_v.`policy_id`
              WHERE legacy_p.`alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$'
                AND legacy_v.`version_id` = v.`copied_from_version_id`
          )
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEGACY_HVAC_COLLECTION_REVISION_REQUIRES_REVIEW';
    END IF;
    DELETE FROM `biz_collection_review_request`
    WHERE (`target_type` = 'SOURCE_ACTIVATION' AND `target_id` = 'SOURCE_MQTT_FREEZE_V1')
       OR (`target_type` = 'ALIAS_ACTIVATION' AND `target_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$')
       OR (`target_type` = 'POLICY_VERSION' AND `target_id` IN (SELECT `version_id` FROM `tmp_legacy_collection_version`));
    DELETE FROM `biz_collection_config_audit_log`
    WHERE (`object_type` = 'DATA_SOURCE' AND `object_id` = 'SOURCE_MQTT_FREEZE_V1')
       OR (`object_type` = 'POINT_ALIAS' AND `object_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$')
       OR `version_id` IN (
           SELECT v.`version_id` FROM `biz_collection_policy_version` v
           JOIN `biz_collection_policy` p ON p.`policy_id` = v.`policy_id`
           WHERE p.`alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$'
       )
       OR `object_id` IN (
           SELECT `policy_id` FROM `biz_collection_policy`
           WHERE `alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$'
       )
       OR `object_id` IN (
           SELECT v.`version_id` FROM `biz_collection_policy_version` v
           JOIN `biz_collection_policy` p ON p.`policy_id` = v.`policy_id`
           WHERE p.`alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$'
       );
    UPDATE `biz_collection_policy` p JOIN `tmp_legacy_collection_policy` x ON x.`policy_id` = p.`policy_id`
    SET p.`active_version_id` = NULL, p.`draft_version_id` = NULL;
    UPDATE `biz_collection_policy_version`
    SET `copied_from_version_id` = NULL
    WHERE `copied_from_version_id` IN (SELECT `version_id` FROM `tmp_legacy_collection_version`);
    DELETE v FROM `biz_collection_policy_version` v JOIN `tmp_legacy_collection_policy` p ON p.`policy_id` = v.`policy_id`;
    DELETE p FROM `biz_collection_policy` p JOIN `tmp_legacy_collection_policy` x ON x.`policy_id` = p.`policy_id`;

    CREATE TEMPORARY TABLE `tmp_legacy_quality_policy` AS
    SELECT `policy_id` FROM `biz_quality_usage_policy` WHERE `point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$';
    CREATE TEMPORARY TABLE `tmp_legacy_quality_version` AS
    SELECT v.`version_id` FROM `biz_quality_usage_policy_version` v
    JOIN `tmp_legacy_quality_policy` p ON p.`policy_id` = v.`policy_id`;
    CREATE TEMPORARY TABLE `tmp_legacy_quality_review` AS
    SELECT r.`request_id`, r.`change_set_id`
    FROM `biz_quality_usage_review_request` r
    WHERE EXISTS (
        SELECT 1 FROM JSON_TABLE(r.`snapshot_json`, '$[*]' COLUMNS (`point_id` VARCHAR(32) PATH '$.pointId')) j
        WHERE j.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
    );
    IF EXISTS (
        SELECT 1 FROM `biz_quality_usage_policy_version` v
        JOIN `biz_quality_usage_policy` p ON p.`policy_id` = v.`policy_id`
        WHERE p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
          AND (v.`version_no` <> 1 OR v.`initial_baseline` <> 1
            OR v.`change_source` <> 'INITIAL_CONSERVATIVE_POLICY')
    ) OR EXISTS (
        SELECT 1 FROM `biz_quality_usage_policy_version` v
        WHERE NOT EXISTS (
            SELECT 1 FROM `biz_quality_usage_policy` p
            WHERE p.`policy_id` = v.`policy_id`
              AND p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
        )
          AND EXISTS (
              SELECT 1
              FROM `biz_quality_usage_policy_version` legacy_v
              JOIN `biz_quality_usage_policy` legacy_p ON legacy_p.`policy_id` = legacy_v.`policy_id`
              WHERE legacy_p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
                AND legacy_v.`version_id` IN (v.`base_active_version_id`, v.`copied_from_version_id`)
          )
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEGACY_HVAC_QUALITY_REVISION_REQUIRES_REVIEW';
    END IF;
    DELETE FROM `biz_quality_usage_audit_log`
    WHERE `object_id` IN (
           SELECT `policy_id` FROM `biz_quality_usage_policy`
           WHERE `point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
       )
       OR `object_id` IN (
           SELECT v.`version_id` FROM `biz_quality_usage_policy_version` v
           JOIN `biz_quality_usage_policy` p ON p.`policy_id` = v.`policy_id`
           WHERE p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
       )
       OR `version_id` IN (
           SELECT v.`version_id` FROM `biz_quality_usage_policy_version` v
           JOIN `biz_quality_usage_policy` p ON p.`policy_id` = v.`policy_id`
           WHERE p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
       )
       OR `object_id` IN (
           SELECT r.`change_set_id` FROM `biz_quality_usage_review_request` r
           WHERE EXISTS (
               SELECT 1 FROM JSON_TABLE(r.`snapshot_json`, '$[*]' COLUMNS (`point_id` VARCHAR(32) PATH '$.pointId')) j
               WHERE j.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
           )
       )
       OR `object_id` IN (
           SELECT r.`request_id` FROM `biz_quality_usage_review_request` r
           WHERE EXISTS (
               SELECT 1 FROM JSON_TABLE(r.`snapshot_json`, '$[*]' COLUMNS (`point_id` VARCHAR(32) PATH '$.pointId')) j
               WHERE j.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
           )
       )
       OR (`object_type` = 'QUALITY_USAGE_MIGRATION' AND `object_id` = 'QUALITY_USAGE_INITIAL_V1');
    DELETE FROM `biz_quality_usage_review_request`
    WHERE `request_id` IN (SELECT `request_id` FROM `tmp_legacy_quality_review`);
    UPDATE `biz_quality_usage_policy` p JOIN `tmp_legacy_quality_policy` x ON x.`policy_id` = p.`policy_id`
    SET p.`current_active_version_id` = NULL, p.`pending_review_request_id` = NULL;
    UPDATE `biz_quality_usage_policy_version` v
    LEFT JOIN `biz_quality_usage_policy_version` base_v ON base_v.`version_id` = v.`base_active_version_id`
    LEFT JOIN `biz_quality_usage_policy` base_p ON base_p.`policy_id` = base_v.`policy_id`
    LEFT JOIN `biz_quality_usage_policy_version` copied_v ON copied_v.`version_id` = v.`copied_from_version_id`
    LEFT JOIN `biz_quality_usage_policy` copied_p ON copied_p.`policy_id` = copied_v.`policy_id`
    SET v.`base_active_version_id` = NULL, v.`copied_from_version_id` = NULL
    WHERE base_p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
       OR copied_p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$';
    DELETE l FROM `biz_quality_usage_policy_level` l JOIN `tmp_legacy_quality_version` v ON v.`version_id` = l.`version_id`;
    DELETE v FROM `biz_quality_usage_policy_version` v JOIN `tmp_legacy_quality_policy` p ON p.`policy_id` = v.`policy_id`;
    DELETE p FROM `biz_quality_usage_policy` p JOIN `tmp_legacy_quality_policy` x ON x.`policy_id` = p.`policy_id`;
    DELETE FROM `biz_quality_usage_change_set`
    WHERE `change_set_id` IN (SELECT DISTINCT `change_set_id` FROM `tmp_legacy_quality_review`);

    DELETE FROM `biz_indicator`
    WHERE `indicator_id` IN ('INDICATOR_WCR_COP_B1','INDICATOR_TOWER_EFF_B1','INDICATOR_PUMP_EFF_B1','INDICATOR_AHU_EFF_B1');
    DELETE FROM `biz_point_alias` WHERE `alias_id` REGEXP '^ALIAS0(0[1-9]|1[0-9])$';
    DELETE FROM `biz_data_point` WHERE `point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$';
    DELETE FROM `biz_equipment`
    WHERE `equip_id` IN ('EQUIP_WCR_B1','EQUIP_TOWER_B1','EQUIP_PUMP_B1','EQUIP_AHU_B1');
    DELETE FROM `biz_point_naming_rule`
    WHERE `rule_id` IN ('RULE_WCR_MAIN','RULE_WCR_PC','RULE_WCR_CT','RULE_WCR_PCD','RULE_AHU_MAIN','RULE_DBO_ENV','RULE_RHO_ENV');
    DELETE FROM `biz_equipment_type` WHERE `type_code` IN ('WCR','WCT','WCP','AHU','Bh','Bs');
    DELETE FROM `biz_data_source` WHERE `source_id` = 'SOURCE_MQTT_FREEZE_V1';

    UPDATE `biz_space` SET `space_name` = '试点设备空间', `space_type` = 'EQUIPMENT_AREA'
    WHERE `space_id` = 'SPACE001' AND `space_name` = '楼顶中央空调机房';
    UPDATE `biz_system_group`
    SET `system_type` = 'ENERGY_MONITORING', `system_group_name` = '试点用能监测系统',
        `group_desc` = '建筑能源设备与测点接入试点'
    WHERE `system_group_id` = 'GROUP001' AND `system_type` = 'HVAC';

    IF EXISTS (
        SELECT 1 FROM `sys_menu`
        WHERE (`path` = '/operations' AND `id` <> 300)
           OR (`path` = '/operations/realtime' AND `id` <> 310)
           OR (`path` = '/operations/realtime/hvac' AND `id` <> 311)
           OR (`path` = '/operations/devices' AND `id` <> 230)
           OR (`path` = '/configuration' AND `id` <> 200)
           OR (`path` = '/configuration/access' AND `id` <> 210)
           OR (`path` = '/configuration/settings' AND `id` <> 240)
           OR (`path` = '/configuration/ingestion' AND `id` <> 250)
           OR (`path` = '/configuration/space' AND `id` <> 260)
           OR (`path` = '/configuration/access/users' AND `id` <> 211)
           OR (`path` = '/configuration/access/roles' AND `id` <> 212)
           OR (`path` = '/configuration/access/buildingAccess' AND `id` <> 223)
           OR (`path` = '/configuration/settings/menus' AND `id` <> 241)
           OR (`path` = '/configuration/space/buildings' AND `id` <> 251)
           OR (`path` = '/operations/devices/businessDevices' AND `id` <> 252)
            OR (`path` = '/configuration/ingestion/products' AND `id` <> 253)
            OR (`path` = '/operations/devices/pendingDevices' AND `id` <> 254)
            OR (`path` = '/configuration/ingestion/protocols' AND `id` <> 255)
            OR (`path` = '/monitor' AND `id` <> 400)
            OR (`path` = '/monitor/monitoring' AND `id` <> 401)
            OR (`path` = '/monitor/trend' AND `id` <> 402)
            OR (`path` = '/monitor/situation' AND `id` <> 403)
            OR (`path` = '/monitor/status' AND `id` <> 404)
            OR (`path` = '/monitor/analysis' AND `id` <> 405)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CANONICAL_MENU_PATH_CONFLICT';
    END IF;
    IF EXISTS (SELECT 1 FROM `sys_menu` WHERE `id` IN (400,401,402,403,404,405)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'MONITOR_MENU_ID_CONFLICT';
    END IF;

    DELETE FROM `sys_role_menu`
    WHERE `menu_id` IN (100,101,110,120,130,131,132,133,140,141,150,151,160,161,220,221,222,231,232,242);
    DELETE FROM `sys_menu`
    WHERE `id` IN (100,101,110,120,130,131,132,133,140,141,150,151,160,161,220,221,222,231,232,242);

    INSERT INTO `sys_menu`
        (`id`,`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`icon`,`visible`,`status`,`sort_order`)
    VALUES
        (300,0,'智慧运维平台','M','/operations',NULL,'monitor',1,1,1),
        (310,300,'实时监测','M','/operations/realtime',NULL,'dashboard',1,1,1),
        (311,310,'设备实时监测','C','/operations/realtime/hvac',NULL,'dashboard',1,1,1),
        (230,300,'设备管理','M','/operations/devices',NULL,'tool',1,1,2),
        (200,0,'能碳配置管理平台','M','/configuration',NULL,'setting',1,1,2),
        (210,200,'用户与权限','M','/configuration/access',NULL,'usergroup',1,1,1),
        (250,200,'数据接入','M','/configuration/ingestion',NULL,'link',1,1,2),
        (260,200,'空间与系统','M','/configuration/space',NULL,'home',1,1,3),
        (240,200,'系统设置','M','/configuration/settings',NULL,'setting',1,1,4),
        (211,210,'用户管理','C','/configuration/access/users',NULL,'user',1,1,1),
        (212,210,'角色与菜单授权','C','/configuration/access/roles',NULL,'team',1,1,2),
        (223,210,'建筑访问审核','C','/configuration/access/buildingAccess',NULL,'key',1,1,3),
        (241,240,'菜单管理','C','/configuration/settings/menus',NULL,'menu',1,1,1),
        (251,260,'建筑管理','C','/configuration/space/buildings',NULL,'home',1,1,1),
        (252,230,'设备台账','C','/operations/devices/businessDevices',NULL,'tool',1,1,1),
        (254,230,'待接入设备','C','/operations/devices/pendingDevices',NULL,'link',1,1,2),
        (253,250,'产品与测点模板','C','/configuration/ingestion/products',NULL,'form',1,1,1),
        (255,250,'设备报文接入','C','/configuration/ingestion/protocols',NULL,'code',1,1,2),
        (400,0,'孪生大屏','M','/monitor',NULL,'monitor',1,1,0),
        (401,400,'监控大屏','C','/monitor/monitoring',NULL,'monitor',1,1,1),
        (402,400,'趋势大屏','C','/monitor/trend',NULL,'trend',1,1,2),
        (403,400,'态势大屏','C','/monitor/situation',NULL,'dashboard',1,1,3),
        (404,400,'状态大屏','C','/monitor/status',NULL,'data-board',1,1,4),
        (405,400,'分析大屏','C','/monitor/analysis',NULL,'analysis',1,1,5)
    ON DUPLICATE KEY UPDATE
        `parent_id` = VALUES(`parent_id`), `menu_name` = VALUES(`menu_name`),
        `menu_type` = VALUES(`menu_type`), `path` = VALUES(`path`), `component` = VALUES(`component`),
        `icon` = VALUES(`icon`), `visible` = VALUES(`visible`), `status` = VALUES(`status`),
        `sort_order` = VALUES(`sort_order`);

    -- Preserve the old dashboard grant one-to-one; new monitor pages need explicit role assignment.
    INSERT IGNORE INTO `sys_role_menu` (`role_id`,`menu_id`)
    SELECT `role_id`, 311 FROM `tmp_legacy_hvac_roles`;

    SELECT COUNT(*) INTO v_count
    FROM `sys_role_menu` rm LEFT JOIN `sys_menu` m ON m.`id` = rm.`menu_id` WHERE m.`id` IS NULL;
    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORPHAN_ROLE_MENU_LINK_AFTER_GOVERNANCE';
    END IF;
    SELECT COUNT(*) INTO v_count
    FROM `sys_menu` child LEFT JOIN `sys_menu` parent ON parent.`id` = child.`parent_id`
    WHERE child.`parent_id` <> 0 AND parent.`id` IS NULL;
    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORPHAN_MENU_NODE_AFTER_GOVERNANCE';
    END IF;
    IF EXISTS (
        SELECT 1 FROM `sys_menu`
        WHERE `path` IN ('/hvac','/hvac-demo','/single','/system','/system/identity','/system/access',
                         '/system/config','/system/users','/system/roles','/system/building-access',
                         '/system/menus','/system/buildings','/system/devices','/system/device-products',
                         '/system/protocol-configurations','/system/device-onboarding')
           OR `path` LIKE '/single/%'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEGACY_MENU_PATH_REMAINS';
    END IF;

    COMMIT;
END //
DELIMITER ;

CALL `retire_legacy_hvac_and_govern_menus`();
DROP PROCEDURE IF EXISTS `retire_legacy_hvac_and_govern_menus`;
