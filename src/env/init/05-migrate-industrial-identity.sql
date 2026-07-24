-- ============================================================================
-- MySQL 旧版 HVAC 模型 -> 工业四层身份模型（一次性、非破坏迁移）
--
-- 执行要求：
--   1. 先备份 iot_platform，并停止应用写入；
--   2. 在 MySQL 8 执行本文件；
--   3. 校验文件末尾的行数，再执行 06 TDengine 迁移；
--   4. *_legacy_20260723 是可回滚备份，本脚本不会物理删除旧业务数据。
-- ============================================================================
USE `iot_platform`;

DELIMITER $$
DROP PROCEDURE IF EXISTS `preflight_industrial_identity`$$
CREATE PROCEDURE `preflight_industrial_identity`()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'biz_equipment'
          AND column_name = 'equip_code'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'biz_equipment.equip_code 已存在：数据库可能已经迁移，禁止重复执行';
    END IF;

    IF EXISTS (
        SELECT building_id, equip_id
        FROM biz_equipment
        GROUP BY building_id, equip_id
        HAVING COUNT(*) > 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '同一建筑存在重复旧设备编码，请先人工消歧';
    END IF;

    IF EXISTS (
        SELECT building_id, point_code
        FROM biz_data_point
        GROUP BY building_id, point_code
        HAVING COUNT(*) > 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '同一建筑存在重复旧测点编码，请先人工消歧';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN (
            'biz_equipment_type',
            'biz_point_naming_rule',
            'biz_point_alias',
            'biz_indicator',
            'biz_system_group_identity',
            'biz_equipment_identity',
            'biz_data_point_identity',
            'migration_system_group_id_map',
            'migration_equipment_id_map',
            'migration_point_id_map',
            'biz_system_group_legacy_20260723',
            'biz_equipment_legacy_20260723',
            'biz_data_point_legacy_20260723'
          )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '迁移备份表已存在，请确认上一次迁移状态';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM biz_equipment e
        LEFT JOIN biz_system_group g
          ON g.building_id=e.building_id AND g.system_group_id=e.system_group_id
        LEFT JOIN biz_space s
          ON s.building_id=e.building_id AND s.space_id=e.space_id
        WHERE g.system_group_id IS NULL OR s.space_id IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '存在跨建筑或失效的设备-系统组/空间关系，请先修复';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM biz_data_point p
        LEFT JOIN biz_equipment e
          ON e.building_id=p.building_id AND e.equip_id=p.equip_id
        WHERE p.equip_id IS NOT NULL AND e.equip_id IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '存在跨建筑或失效的测点-设备关系，请先修复';
    END IF;

    IF EXISTS (
        SELECT canonical_code
        FROM (
            SELECT building_id,
                   CASE point_code
                     WHEN 'WCR1_Flow' THEN 'WCR1_GW'
                     WHEN 'TOWER1_TCWin' THEN 'WCR1_CT_TWin'
                     WHEN 'TOWER1_TCWout' THEN 'WCR1_CT_TWout'
                     WHEN 'TOWER1_TWB' THEN 'WCR1_CT_TWB'
                     WHEN 'PUMP1_Flow' THEN 'WCR1_Pc_GW'
                     WHEN 'PUMP1_Pout' THEN 'WCR1_Pc_Pout'
                     WHEN 'PUMP1_Pin' THEN 'WCR1_Pc_Pin'
                     WHEN 'PUMP1_Z' THEN 'WCR1_Pc_Z'
                     WHEN 'PUMP1_Power' THEN 'WCR1_Pc_PPE'
                     WHEN 'DBO_TDB' THEN 'DBO'
                     WHEN 'DBO_RH' THEN 'RHO'
                     ELSE point_code
                   END AS canonical_code
            FROM biz_data_point
        ) mapped
        GROUP BY building_id, canonical_code
        HAVING COUNT(*) > 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '旧测点转换后产生建筑内标准编码冲突，请先人工消歧';
    END IF;

    IF EXISTS (
        SELECT 1 FROM biz_equipment
        WHERE type_code NOT IN ('WCR','WCT','WCP','AHU','Bh','Bs')
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '存在未注册的设备类型，请先补充设备类型字典迁移规则';
    END IF;
END$$
CALL `preflight_industrial_identity`()$$
DROP PROCEDURE `preflight_industrial_identity`$$
DELIMITER ;

-- 顶级空间从魔法值 "0" 统一改为 NULL；原 space_id 本身已经是稳定内部ID。
UPDATE `biz_space`
SET `parent_space_id` = NULL
WHERE `parent_space_id` = '0';

CREATE TABLE `biz_equipment_type` (
    `type_code` VARCHAR(20) NOT NULL,
    `type_name` VARCHAR(100) NOT NULL,
    `asset_code_prefix` VARCHAR(20) NOT NULL,
    `equip_category` VARCHAR(50) NOT NULL,
    `standard_source` VARCHAR(32) NOT NULL,
    `status` TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (`type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `biz_equipment_type`
(`type_code`,`type_name`,`asset_code_prefix`,`equip_category`,`standard_source`,`status`)
VALUES
('WCR','水冷式冷水机组','WCR','CHILLER','HANDOFF',1),
('WCT','冷却塔','TOWER','TOWER','FREEZE_EXTENSION',1),
('WCP','冷冻水泵','PUMP','PUMP','FREEZE_EXTENSION',1),
('AHU','空气处理机组','AHU','AHU','FREEZE_EXTENSION',1),
('Bh','热水锅炉','Bh','BOILER','HANDOFF',1),
('Bs','蒸汽锅炉','Bs','BOILER','HANDOFF',1);

CREATE TABLE `biz_point_naming_rule` (
    `rule_id` VARCHAR(32) NOT NULL,
    `standard_version` VARCHAR(32) NOT NULL,
    `family_code` VARCHAR(20) NOT NULL,
    `component_code` VARCHAR(20) NOT NULL,
    `code_template` VARCHAR(100) NOT NULL,
    `standard_source` VARCHAR(32) NOT NULL,
    `status` TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (`rule_id`),
    UNIQUE KEY `uk_point_rule_semantic`
        (`standard_version`,`family_code`,`component_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `biz_point_naming_rule`
(`rule_id`,`standard_version`,`family_code`,`component_code`,`code_template`,`standard_source`,`status`)
VALUES
('RULE_WCR_MAIN','HANDOFF_V1','WCR','MAIN','WCR[n]','HANDOFF',1),
('RULE_WCR_PC','HANDOFF_V1','WCR','Pc','WCR[n]_Pc','HANDOFF',1),
('RULE_WCR_CT','HANDOFF_V1','WCR','CT','WCR[n]_CT','HANDOFF',1),
('RULE_WCR_PCD','HANDOFF_V1','WCR','Pcd','WCR[n]_Pcd','HANDOFF',1),
('RULE_AHU_MAIN','FREEZE_V1','AHU','MAIN','AHU[n]','FREEZE_EXTENSION',1),
('RULE_DBO_ENV','HANDOFF_V1','DBO','ENV','DBO','HANDOFF',1),
('RULE_RHO_ENV','HANDOFF_V1','RHO','ENV','RHO','HANDOFF',1);

-- 映射表只在迁移过程中使用；SHA-256 前32位生成确定且全局唯一的内部ID。
CREATE TABLE `migration_system_group_id_map` (
    `building_id` VARCHAR(32) NOT NULL,
    `old_group_id` VARCHAR(50) NOT NULL,
    `new_group_id` VARCHAR(32) NOT NULL,
    PRIMARY KEY (`building_id`,`old_group_id`),
    UNIQUE KEY (`new_group_id`)
) ENGINE=InnoDB;

INSERT INTO `migration_system_group_id_map`
SELECT `building_id`, `system_group_id`,
       UPPER(SUBSTRING(SHA2(CONCAT('GROUP|',`building_id`,'|',`system_group_id`),256),1,32))
FROM `biz_system_group`;

CREATE TABLE `migration_equipment_id_map` (
    `building_id` VARCHAR(32) NOT NULL,
    `old_equip_id` VARCHAR(50) NOT NULL,
    `new_equip_id` VARCHAR(32) NOT NULL,
    PRIMARY KEY (`building_id`,`old_equip_id`),
    UNIQUE KEY (`new_equip_id`)
) ENGINE=InnoDB;

INSERT INTO `migration_equipment_id_map`
SELECT `building_id`, `equip_id`,
       UPPER(SUBSTRING(SHA2(CONCAT('EQUIP|',`building_id`,'|',`equip_id`),256),1,32))
FROM `biz_equipment`;

CREATE TABLE `migration_point_id_map` (
    `building_id` VARCHAR(32) NOT NULL,
    `old_point_code` VARCHAR(100) NOT NULL,
    `new_point_id` VARCHAR(32) NOT NULL,
    PRIMARY KEY (`building_id`,`old_point_code`),
    UNIQUE KEY (`new_point_id`)
) ENGINE=InnoDB;

INSERT INTO `migration_point_id_map`
SELECT `building_id`, `point_code`,
       UPPER(SUBSTRING(SHA2(CONCAT('POINT|',`building_id`,'|',`point_code`),256),1,32))
FROM `biz_data_point`;

CREATE TABLE `biz_system_group_identity` LIKE `biz_system_group`;
ALTER TABLE `biz_system_group_identity`
    DROP PRIMARY KEY,
    CHANGE `system_group_id` `system_group_id` VARCHAR(32) NOT NULL,
    ADD COLUMN `system_group_code` VARCHAR(50) NOT NULL AFTER `system_group_id`,
    ADD PRIMARY KEY (`system_group_id`),
    ADD UNIQUE KEY `uk_group_building_code` (`building_id`,`system_group_code`),
    ADD UNIQUE KEY `uk_group_id_building` (`system_group_id`,`building_id`);

INSERT INTO `biz_system_group_identity`
(`system_group_id`,`system_group_code`,`building_id`,`system_type`,`system_group_name`,
 `group_desc`,`design_cop`,`design_capacity`,`annual_budget`,`create_time`,`update_time`,`del_flag`)
SELECT m.`new_group_id`, g.`system_group_id`, g.`building_id`, g.`system_type`,
       g.`system_group_name`, g.`group_desc`, g.`design_cop`, g.`design_capacity`,
       g.`annual_budget`, g.`create_time`, g.`update_time`, g.`del_flag`
FROM `biz_system_group` g
JOIN `migration_system_group_id_map` m
  ON m.`building_id`=g.`building_id` AND m.`old_group_id`=g.`system_group_id`;

CREATE TABLE `biz_equipment_identity` LIKE `biz_equipment`;
ALTER TABLE `biz_equipment_identity`
    DROP PRIMARY KEY,
    CHANGE `equip_id` `equip_id` VARCHAR(32) NOT NULL,
    ADD COLUMN `equip_code` VARCHAR(50) NOT NULL AFTER `equip_id`,
    CHANGE `system_group_id` `system_group_id` VARCHAR(32) NOT NULL,
    ADD PRIMARY KEY (`equip_id`),
    ADD UNIQUE KEY `uk_equipment_building_code` (`building_id`,`equip_code`),
    ADD UNIQUE KEY `uk_equipment_id_building` (`equip_id`,`building_id`);

INSERT INTO `biz_equipment_identity`
(`equip_id`,`equip_code`,`equip_name`,`type_code`,`equip_category`,`system_group_id`,
 `building_id`,`space_id`,`manufacturer`,`rated_capacity`,`rated_power`,`design_cop`,
 `create_time`,`update_time`,`del_flag`)
SELECT em.`new_equip_id`, e.`equip_id`, e.`equip_name`, e.`type_code`, e.`equip_category`,
       gm.`new_group_id`, e.`building_id`, e.`space_id`, e.`manufacturer`,
       e.`rated_capacity`, e.`rated_power`, e.`design_cop`,
       e.`create_time`, e.`update_time`, e.`del_flag`
FROM `biz_equipment` e
JOIN `migration_equipment_id_map` em
  ON em.`building_id`=e.`building_id` AND em.`old_equip_id`=e.`equip_id`
JOIN `migration_system_group_id_map` gm
  ON gm.`building_id`=e.`building_id` AND gm.`old_group_id`=e.`system_group_id`;

-- 标准编码转换只改变平台标准层；old point_code 完整保留到别名表。
CREATE TABLE `biz_data_point_identity` (
    `point_id` VARCHAR(32) NOT NULL,
    `point_code` VARCHAR(100) NOT NULL,
    `point_name` VARCHAR(100) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `system_group_id` VARCHAR(32) DEFAULT NULL,
    `equip_id` VARCHAR(32) DEFAULT NULL,
    `naming_rule_id` VARCHAR(32) NOT NULL,
    `family_code` VARCHAR(20) NOT NULL,
    `component_code` VARCHAR(20) NOT NULL,
    `suffix_code` VARCHAR(20) NOT NULL,
    `data_type` VARCHAR(20) NOT NULL,
    `unit` VARCHAR(20) DEFAULT NULL,
    `is_for_calc` TINYINT(1) NOT NULL DEFAULT 0,
    `default_value` DECIMAL(12,4) DEFAULT NULL,
    `value_max` DECIMAL(12,4) DEFAULT NULL,
    `value_min` DECIMAL(12,4) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ONLINE',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `del_flag` TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`point_id`),
    UNIQUE KEY `uk_point_building_code` (`building_id`,`point_code`),
    UNIQUE KEY `uk_point_id_building` (`point_id`,`building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `biz_data_point_identity`
(`point_id`,`point_code`,`point_name`,`building_id`,`system_group_id`,`equip_id`,
 `naming_rule_id`,`family_code`,`component_code`,`suffix_code`,`data_type`,`unit`,
 `is_for_calc`,`default_value`,`value_max`,`value_min`,`status`,`create_time`,`update_time`,`del_flag`)
SELECT pm.`new_point_id`,
       CASE p.`point_code`
         WHEN 'WCR1_Flow' THEN 'WCR1_GW'
         WHEN 'TOWER1_TCWin' THEN 'WCR1_CT_TWin'
         WHEN 'TOWER1_TCWout' THEN 'WCR1_CT_TWout'
         WHEN 'TOWER1_TWB' THEN 'WCR1_CT_TWB'
         WHEN 'PUMP1_Flow' THEN 'WCR1_Pc_GW'
         WHEN 'PUMP1_Pout' THEN 'WCR1_Pc_Pout'
         WHEN 'PUMP1_Pin' THEN 'WCR1_Pc_Pin'
         WHEN 'PUMP1_Z' THEN 'WCR1_Pc_Z'
         WHEN 'PUMP1_Power' THEN 'WCR1_Pc_PPE'
         WHEN 'DBO_TDB' THEN 'DBO'
         WHEN 'DBO_RH' THEN 'RHO'
         ELSE p.`point_code`
       END,
       p.`point_name`, p.`building_id`, gm.`new_group_id`, em.`new_equip_id`,
       CASE
         WHEN p.`point_code` LIKE 'TOWER%' THEN 'RULE_WCR_CT'
         WHEN p.`point_code` LIKE 'PUMP%' THEN 'RULE_WCR_PC'
         WHEN p.`point_code` LIKE 'AHU%' THEN 'RULE_AHU_MAIN'
         WHEN p.`point_code`='DBO_TDB' THEN 'RULE_DBO_ENV'
         WHEN p.`point_code`='DBO_RH' THEN 'RULE_RHO_ENV'
         ELSE 'RULE_WCR_MAIN'
       END,
       CASE
         WHEN p.`point_code`='DBO_TDB' THEN 'DBO'
         WHEN p.`point_code`='DBO_RH' THEN 'RHO'
         WHEN p.`point_code` LIKE 'AHU%' THEN 'AHU'
         ELSE 'WCR'
       END,
       CASE
         WHEN p.`point_code` LIKE 'TOWER%' THEN 'CT'
         WHEN p.`point_code` LIKE 'PUMP%' THEN 'Pc'
         WHEN p.`point_code` LIKE 'DBO_%' THEN 'ENV'
         ELSE 'MAIN'
       END,
       CASE p.`suffix_code`
         WHEN 'Flow' THEN 'GW'
         WHEN 'TCWin' THEN 'TWin'
         WHEN 'TCWout' THEN 'TWout'
         WHEN 'Power' THEN 'PPE'
         ELSE p.`suffix_code`
       END,
       p.`data_type`, p.`unit`, p.`is_for_calc`, p.`default_value`,
       p.`value_max`, p.`value_min`, p.`status`,
       p.`create_time`, p.`update_time`, p.`del_flag`
FROM `biz_data_point` p
JOIN `migration_point_id_map` pm
  ON pm.`building_id`=p.`building_id` AND pm.`old_point_code`=p.`point_code`
LEFT JOIN `migration_equipment_id_map` em
  ON em.`building_id`=p.`building_id` AND em.`old_equip_id`=p.`equip_id`
LEFT JOIN `biz_equipment` e
  ON e.`building_id`=p.`building_id` AND e.`equip_id`=p.`equip_id`
LEFT JOIN `migration_system_group_id_map` gm
  ON gm.`building_id`=e.`building_id` AND gm.`old_group_id`=e.`system_group_id`;

CREATE TABLE `biz_point_alias` (
    `alias_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `source_system` VARCHAR(50) NOT NULL,
    `source_point_code` VARCHAR(100) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `status` TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (`alias_id`),
    UNIQUE KEY `uk_alias_source` (`building_id`,`source_system`,`source_point_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `biz_point_alias`
(`alias_id`,`building_id`,`source_system`,`source_point_code`,`point_id`,`status`)
SELECT UPPER(SUBSTRING(SHA2(CONCAT('ALIAS|',p.`building_id`,'|MQTT_FREEZE_V1|',p.`point_code`),256),1,32)),
       p.`building_id`, 'MQTT_FREEZE_V1', p.`point_code`, m.`new_point_id`, 1
FROM `biz_data_point` p
JOIN `migration_point_id_map` m
  ON m.`building_id`=p.`building_id` AND m.`old_point_code`=p.`point_code`;

CREATE TABLE `biz_indicator` (
    `indicator_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `indicator_code` VARCHAR(100) NOT NULL,
    `scope_type` VARCHAR(20) NOT NULL,
    `scope_id` VARCHAR(32) NOT NULL,
    `equip_id` VARCHAR(32) DEFAULT NULL,
    `system_group_id` VARCHAR(32) DEFAULT NULL,
    `status` TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (`indicator_id`),
    UNIQUE KEY `uk_indicator_scope`
        (`building_id`,`indicator_code`,`scope_type`,`scope_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 原表原样保留为 legacy；RENAME TABLE 在 MySQL 中是原子元数据切换。
RENAME TABLE
    `biz_system_group` TO `biz_system_group_legacy_20260723`,
    `biz_system_group_identity` TO `biz_system_group`,
    `biz_equipment` TO `biz_equipment_legacy_20260723`,
    `biz_equipment_identity` TO `biz_equipment`,
    `biz_data_point` TO `biz_data_point_legacy_20260723`,
    `biz_data_point_identity` TO `biz_data_point`;

-- 在数据完成复制后再加关系约束，避免半迁移结构提前影响旧表读取。
ALTER TABLE `biz_system_group`
    ADD CONSTRAINT `fk_group_building`
        FOREIGN KEY (`building_id`) REFERENCES `building` (`building_id`);

ALTER TABLE `biz_equipment`
    ADD CONSTRAINT `fk_equipment_type`
        FOREIGN KEY (`type_code`) REFERENCES `biz_equipment_type` (`type_code`),
    ADD CONSTRAINT `fk_equipment_group_building`
        FOREIGN KEY (`system_group_id`,`building_id`)
        REFERENCES `biz_system_group` (`system_group_id`,`building_id`);

ALTER TABLE `biz_data_point`
    ADD CONSTRAINT `fk_point_rule`
        FOREIGN KEY (`naming_rule_id`) REFERENCES `biz_point_naming_rule` (`rule_id`),
    ADD CONSTRAINT `fk_point_group_building`
        FOREIGN KEY (`system_group_id`,`building_id`)
        REFERENCES `biz_system_group` (`system_group_id`,`building_id`),
    ADD CONSTRAINT `fk_point_equipment_building`
        FOREIGN KEY (`equip_id`,`building_id`)
        REFERENCES `biz_equipment` (`equip_id`,`building_id`);

ALTER TABLE `biz_point_alias`
    ADD CONSTRAINT `fk_alias_point_building`
        FOREIGN KEY (`point_id`,`building_id`)
        REFERENCES `biz_data_point` (`point_id`,`building_id`);

-- 验收：三组 new_rows 必须分别等于 legacy_rows；alias_rows 必须等于 point_rows。
SELECT 'system_group' AS entity,
       (SELECT COUNT(*) FROM biz_system_group_legacy_20260723) AS legacy_rows,
       (SELECT COUNT(*) FROM biz_system_group) AS new_rows
UNION ALL
SELECT 'equipment',
       (SELECT COUNT(*) FROM biz_equipment_legacy_20260723),
       (SELECT COUNT(*) FROM biz_equipment)
UNION ALL
SELECT 'data_point',
       (SELECT COUNT(*) FROM biz_data_point_legacy_20260723),
       (SELECT COUNT(*) FROM biz_data_point);

SELECT
    (SELECT COUNT(*) FROM biz_data_point) AS point_rows,
    (SELECT COUNT(*) FROM biz_point_alias) AS alias_rows;
