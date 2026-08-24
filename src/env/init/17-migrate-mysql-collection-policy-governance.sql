-- 数据源与采集策略治理的 MySQL 8 增量迁移。
-- 本脚本只治理平台可信北向来源与采集策略配置；不保存连接凭据、不下发设备参数，
-- 也不控制 TDengine 保留或 Q0/Q1/Q2 使用范围。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

-- 数据源表先独立创建。后续过程会校验其关键结构，避免 CREATE TABLE IF NOT EXISTS
-- 在已有不兼容表时静默继续。
CREATE TABLE IF NOT EXISTS `biz_data_source` (
    `source_id` VARCHAR(32) NOT NULL COMMENT '平台内部稳定数据源ID',
    `source_code` VARCHAR(50) NOT NULL COMMENT '全局唯一的大写技术编码',
    `source_name` VARCHAR(100) NOT NULL COMMENT '展示名称',
    `building_id` VARCHAR(32) NOT NULL COMMENT '首版一源一建筑归属',
    `source_category` VARCHAR(32) NOT NULL COMMENT '首版固定为DEVICE_ACCESS',
    `transport_type` VARCHAR(20) NOT NULL COMMENT 'MQTT或HTTP',
    `status` VARCHAR(20) NOT NULL COMMENT 'DRAFT、ENABLED、DISABLED',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '展示说明',
    `config_revision` INT NOT NULL DEFAULT 0 COMMENT '聚合配置修订号',
    `runtime_revision` BIGINT NOT NULL DEFAULT 0 COMMENT '正式运行配置修订号',
    `create_by` BIGINT DEFAULT NULL COMMENT '创建用户；系统迁移为空',
    `update_by` BIGINT DEFAULT NULL COMMENT '最近修改用户；系统迁移为空',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`source_id`),
    UNIQUE KEY `uk_data_source_code` (`source_code`),
    UNIQUE KEY `uk_data_source_id_building` (`source_id`, `building_id`),
    KEY `idx_data_source_building_status` (`building_id`, `status`),
    CONSTRAINT `chk_data_source_code`
        CHECK (`source_code` REGEXP '^[A-Z0-9_-]+$'),
    CONSTRAINT `chk_data_source_category`
        CHECK (`source_category` = 'DEVICE_ACCESS'),
    CONSTRAINT `chk_data_source_transport`
        CHECK (`transport_type` IN ('MQTT', 'HTTP')),
    CONSTRAINT `chk_data_source_status`
        CHECK (`status` IN ('DRAFT', 'ENABLED', 'DISABLED')),
    CONSTRAINT `chk_data_source_config_revision`
        CHECK (`config_revision` >= 0),
    CONSTRAINT `chk_data_source_runtime_revision`
        CHECK (`runtime_revision` >= 0),
    CONSTRAINT `fk_data_source_building`
        FOREIGN KEY (`building_id`) REFERENCES `building` (`building_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可信北向数据源业务档案';

DROP PROCEDURE IF EXISTS `migrate_collection_policy_governance`;
DELIMITER //
CREATE PROCEDURE `migrate_collection_policy_governance`()
BEGIN
    DECLARE v_legacy_alias_count INT DEFAULT 0;
    DECLARE v_legacy_point_count INT DEFAULT 0;
    DECLARE v_source_count INT DEFAULT 0;
    DECLARE v_policy_count INT DEFAULT 0;
    DECLARE v_initial_version_count INT DEFAULT 0;
    DECLARE v_migration_audit_count INT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.`TABLES`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'building'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.`TABLES`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_data_point'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.`TABLES`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_PREREQUISITE_MISSING';
    END IF;

    -- 先验证新表核心身份和组合唯一键；若已有同名但不兼容结构，停止而不猜测修复。
    IF (SELECT COUNT(*) FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_data_source'
          AND `COLUMN_NAME` IN ('source_id', 'source_code', 'source_name', 'building_id',
              'source_category', 'transport_type', 'status', 'description',
              'config_revision', 'runtime_revision', 'create_by', 'update_by',
              'create_time', 'update_time')) <> 14
       OR EXISTS (
           SELECT 1 FROM information_schema.`COLUMNS`
           WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_data_source'
             AND ((`COLUMN_NAME` = 'source_id' AND (LOWER(`COLUMN_TYPE`) <> 'varchar(32)' OR `IS_NULLABLE` <> 'NO'))
               OR (`COLUMN_NAME` = 'source_code' AND (LOWER(`COLUMN_TYPE`) <> 'varchar(50)' OR `IS_NULLABLE` <> 'NO'))
               OR (`COLUMN_NAME` = 'source_name' AND (LOWER(`COLUMN_TYPE`) <> 'varchar(100)' OR `IS_NULLABLE` <> 'NO'))
               OR (`COLUMN_NAME` = 'building_id' AND (LOWER(`COLUMN_TYPE`) <> 'varchar(32)' OR `IS_NULLABLE` <> 'NO'))
               OR (`COLUMN_NAME` = 'source_category' AND (LOWER(`COLUMN_TYPE`) <> 'varchar(32)' OR `IS_NULLABLE` <> 'NO'))
               OR (`COLUMN_NAME` = 'transport_type' AND (LOWER(`COLUMN_TYPE`) <> 'varchar(20)' OR `IS_NULLABLE` <> 'NO'))
               OR (`COLUMN_NAME` = 'status' AND (LOWER(`COLUMN_TYPE`) <> 'varchar(20)' OR `IS_NULLABLE` <> 'NO'))
               OR (`COLUMN_NAME` = 'description' AND LOWER(`COLUMN_TYPE`) <> 'varchar(500)')
               OR (`COLUMN_NAME` = 'config_revision' AND LOWER(`COLUMN_TYPE`) NOT IN ('int', 'int(11)'))
               OR (`COLUMN_NAME` = 'runtime_revision' AND LOWER(`COLUMN_TYPE`) <> 'bigint')
               OR (`COLUMN_NAME` IN ('create_by', 'update_by') AND LOWER(`COLUMN_TYPE`) <> 'bigint')
               OR (`COLUMN_NAME` IN ('create_time', 'update_time') AND LOWER(`COLUMN_TYPE`) <> 'datetime(3)'))
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_DATA_SOURCE_STRUCTURE_CONFLICT';
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.`STATISTICS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_data_source'
          AND `INDEX_NAME` = 'uk_data_source_code') <> 1
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.`STATISTICS`
           WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_data_source'
             AND `INDEX_NAME` = 'uk_data_source_code' AND `NON_UNIQUE` = 0
             AND `SEQ_IN_INDEX` = 1 AND `COLUMN_NAME` = 'source_code'
       )
       OR (SELECT COUNT(*) FROM information_schema.`STATISTICS`
           WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_data_source'
             AND `INDEX_NAME` = 'uk_data_source_id_building') <> 2
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.`STATISTICS`
           WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_data_source'
             AND `INDEX_NAME` = 'uk_data_source_id_building' AND `NON_UNIQUE` = 0
             AND `SEQ_IN_INDEX` = 1 AND `COLUMN_NAME` = 'source_id'
       )
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.`STATISTICS`
           WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_data_source'
             AND `INDEX_NAME` = 'uk_data_source_id_building' AND `NON_UNIQUE` = 0
             AND `SEQ_IN_INDEX` = 2 AND `COLUMN_NAME` = 'building_id'
       )
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.`KEY_COLUMN_USAGE`
           WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_data_source'
             AND `CONSTRAINT_NAME` = 'fk_data_source_building'
             AND `COLUMN_NAME` = 'building_id'
             AND `REFERENCED_TABLE_SCHEMA` = DATABASE()
             AND `REFERENCED_TABLE_NAME` = 'building'
             AND `REFERENCED_COLUMN_NAME` = 'building_id'
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_DATA_SOURCE_STRUCTURE_CONFLICT';
    END IF;

    -- 既有别名需增量补齐字段。逐项处理使 DDL 中断后可以安全重跑。
    IF EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `COLUMN_NAME` = 'source_id'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.`COLUMNS`
            WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
              AND `COLUMN_NAME` = 'source_id'
              AND (LOWER(`COLUMN_TYPE`) <> 'varchar(32)' OR `IS_NULLABLE` <> 'YES')
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_COLUMN_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_point_alias`
            ADD COLUMN `source_id` VARCHAR(32) DEFAULT NULL
                COMMENT '正式关联的数据源' AFTER `building_id`;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `COLUMN_NAME` = 'revision'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.`COLUMNS`
            WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
              AND `COLUMN_NAME` = 'revision'
              AND (LOWER(`COLUMN_TYPE`) NOT IN ('int', 'int(11)') OR `IS_NULLABLE` <> 'NO')
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_COLUMN_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_point_alias`
            ADD COLUMN `revision` INT NOT NULL DEFAULT 0
                COMMENT '草稿并发修订号' AFTER `status`;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `COLUMN_NAME` = 'create_by'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.`COLUMNS`
            WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
              AND `COLUMN_NAME` = 'create_by'
              AND (LOWER(`COLUMN_TYPE`) <> 'bigint' OR `IS_NULLABLE` <> 'YES')
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_COLUMN_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_point_alias`
            ADD COLUMN `create_by` BIGINT DEFAULT NULL
                COMMENT '草稿创建人；系统迁移为空' AFTER `revision`;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `COLUMN_NAME` = 'update_by'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.`COLUMNS`
            WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
              AND `COLUMN_NAME` = 'update_by'
              AND (LOWER(`COLUMN_TYPE`) <> 'bigint' OR `IS_NULLABLE` <> 'YES')
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_COLUMN_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_point_alias`
            ADD COLUMN `update_by` BIGINT DEFAULT NULL
                COMMENT '最近修改人；系统迁移为空' AFTER `create_by`;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `COLUMN_NAME` = 'create_time'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.`COLUMNS`
            WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
              AND `COLUMN_NAME` = 'create_time'
              AND (LOWER(`COLUMN_TYPE`) <> 'datetime(3)' OR `IS_NULLABLE` <> 'NO')
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_COLUMN_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_point_alias`
            ADD COLUMN `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                AFTER `update_by`;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `COLUMN_NAME` = 'update_time'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.`COLUMNS`
            WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
              AND `COLUMN_NAME` = 'update_time'
              AND (LOWER(`COLUMN_TYPE`) <> 'datetime(3)' OR `IS_NULLABLE` <> 'NO')
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_COLUMN_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_point_alias`
            ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;
    END IF;

    IF EXISTS (
        SELECT 1 FROM `biz_point_alias`
        WHERE `status` NOT IN (0, 1, 2) OR `status` IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_STATUS_CONFLICT';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`TABLE_CONSTRAINTS`
        WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `CONSTRAINT_NAME` = 'chk_collection_alias_status'
          AND `CONSTRAINT_TYPE` <> 'CHECK'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_CONSTRAINT_CONFLICT';
    ELSEIF NOT EXISTS (
        SELECT 1 FROM information_schema.`TABLE_CONSTRAINTS`
        WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `CONSTRAINT_NAME` = 'chk_collection_alias_status'
          AND `CONSTRAINT_TYPE` = 'CHECK'
    ) THEN
        ALTER TABLE `biz_point_alias`
            ADD CONSTRAINT `chk_collection_alias_status`
                CHECK (`status` IN (0, 1, 2));
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`STATISTICS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `INDEX_NAME` = 'uk_alias_id_building'
    ) THEN
        IF (SELECT COUNT(*) FROM information_schema.`STATISTICS`
            WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
              AND `INDEX_NAME` = 'uk_alias_id_building') <> 2
           OR NOT EXISTS (
               SELECT 1 FROM information_schema.`STATISTICS`
               WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
                 AND `INDEX_NAME` = 'uk_alias_id_building' AND `NON_UNIQUE` = 0
                 AND `SEQ_IN_INDEX` = 1 AND `COLUMN_NAME` = 'alias_id'
           )
           OR NOT EXISTS (
               SELECT 1 FROM information_schema.`STATISTICS`
               WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
                 AND `INDEX_NAME` = 'uk_alias_id_building' AND `NON_UNIQUE` = 0
                 AND `SEQ_IN_INDEX` = 2 AND `COLUMN_NAME` = 'building_id'
           ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_INDEX_CONFLICT';
        END IF;
    ELSE
        IF EXISTS (
            SELECT 1 FROM `biz_point_alias`
            GROUP BY `alias_id`, `building_id` HAVING COUNT(*) > 1
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_INDEX_DATA_CONFLICT';
        END IF;
        ALTER TABLE `biz_point_alias`
            ADD UNIQUE KEY `uk_alias_id_building` (`alias_id`, `building_id`);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`STATISTICS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `INDEX_NAME` = 'idx_alias_source_building'
    ) THEN
        IF (SELECT COUNT(*) FROM information_schema.`STATISTICS`
            WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
              AND `INDEX_NAME` = 'idx_alias_source_building') <> 2
           OR NOT EXISTS (
               SELECT 1 FROM information_schema.`STATISTICS`
               WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
                 AND `INDEX_NAME` = 'idx_alias_source_building'
                 AND `SEQ_IN_INDEX` = 1 AND `COLUMN_NAME` = 'source_id'
           )
           OR NOT EXISTS (
               SELECT 1 FROM information_schema.`STATISTICS`
               WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
                 AND `INDEX_NAME` = 'idx_alias_source_building'
                 AND `SEQ_IN_INDEX` = 2 AND `COLUMN_NAME` = 'building_id'
           ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_INDEX_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_point_alias`
            ADD KEY `idx_alias_source_building` (`source_id`, `building_id`);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.`KEY_COLUMN_USAGE`
        WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `CONSTRAINT_NAME` = 'fk_alias_point_building'
          AND `COLUMN_NAME` = 'point_id'
          AND `REFERENCED_TABLE_SCHEMA` = DATABASE()
          AND `REFERENCED_TABLE_NAME` = 'biz_data_point'
          AND `REFERENCED_COLUMN_NAME` = 'point_id'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM `biz_point_alias` a
            LEFT JOIN `biz_data_point` p
              ON p.`point_id` = a.`point_id` AND p.`building_id` = a.`building_id`
            WHERE p.`point_id` IS NULL
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_POINT_REFERENCE_CONFLICT';
        END IF;
        ALTER TABLE `biz_point_alias`
            ADD CONSTRAINT `fk_alias_point_building`
                FOREIGN KEY (`point_id`, `building_id`)
                REFERENCES `biz_data_point` (`point_id`, `building_id`)
                ON DELETE RESTRICT ON UPDATE RESTRICT;
    END IF;

    IF EXISTS (
        SELECT 1 FROM `biz_point_alias` a
        LEFT JOIN `biz_data_source` s
          ON s.`source_id` = a.`source_id` AND s.`building_id` = a.`building_id`
        WHERE a.`source_id` IS NOT NULL AND s.`source_id` IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_SOURCE_REFERENCE_CONFLICT';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`KEY_COLUMN_USAGE`
        WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
          AND `CONSTRAINT_NAME` = 'fk_alias_source_building'
    ) THEN
        IF (SELECT COUNT(*) FROM information_schema.`KEY_COLUMN_USAGE`
            WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
              AND `CONSTRAINT_NAME` = 'fk_alias_source_building') <> 2
           OR NOT EXISTS (
               SELECT 1 FROM information_schema.`KEY_COLUMN_USAGE`
               WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
                 AND `CONSTRAINT_NAME` = 'fk_alias_source_building'
                 AND `ORDINAL_POSITION` = 1 AND `COLUMN_NAME` = 'source_id'
                 AND `REFERENCED_TABLE_SCHEMA` = DATABASE()
                 AND `REFERENCED_TABLE_NAME` = 'biz_data_source'
                 AND `REFERENCED_COLUMN_NAME` = 'source_id'
           )
           OR NOT EXISTS (
               SELECT 1 FROM information_schema.`KEY_COLUMN_USAGE`
               WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias'
                 AND `CONSTRAINT_NAME` = 'fk_alias_source_building'
                 AND `ORDINAL_POSITION` = 2 AND `COLUMN_NAME` = 'building_id'
                 AND `REFERENCED_TABLE_SCHEMA` = DATABASE()
                 AND `REFERENCED_TABLE_NAME` = 'biz_data_source'
                 AND `REFERENCED_COLUMN_NAME` = 'building_id'
           ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_ALIAS_FK_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_point_alias`
            ADD CONSTRAINT `fk_alias_source_building`
                FOREIGN KEY (`source_id`, `building_id`)
                REFERENCES `biz_data_source` (`source_id`, `building_id`)
                ON DELETE RESTRICT ON UPDATE RESTRICT;
    END IF;

    CREATE TABLE IF NOT EXISTS `biz_collection_policy` (
        `policy_id` VARCHAR(32) NOT NULL COMMENT '策略稳定身份',
        `source_id` VARCHAR(32) NOT NULL COMMENT '所属数据源',
        `alias_id` VARCHAR(32) NOT NULL COMMENT '一对一来源别名',
        `building_id` VARCHAR(32) NOT NULL COMMENT '权限与组合外键范围',
        `active_version_id` VARCHAR(32) DEFAULT NULL COMMENT '当前有效版本指针',
        `draft_version_id` VARCHAR(32) DEFAULT NULL COMMENT '当前草稿版本指针',
        `create_by` BIGINT DEFAULT NULL COMMENT '创建人；系统迁移为空',
        `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
        `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
            ON UPDATE CURRENT_TIMESTAMP(3),
        PRIMARY KEY (`policy_id`),
        UNIQUE KEY `uk_collection_policy_alias` (`alias_id`),
        KEY `idx_collection_policy_source_building` (`source_id`, `building_id`),
        KEY `idx_collection_policy_active_version` (`active_version_id`),
        KEY `idx_collection_policy_draft_version` (`draft_version_id`),
        CONSTRAINT `fk_collection_policy_source_building`
            FOREIGN KEY (`source_id`, `building_id`)
            REFERENCES `biz_data_source` (`source_id`, `building_id`)
            ON DELETE RESTRICT ON UPDATE RESTRICT,
        CONSTRAINT `fk_collection_policy_alias_building`
            FOREIGN KEY (`alias_id`, `building_id`)
            REFERENCES `biz_point_alias` (`alias_id`, `building_id`)
            ON DELETE RESTRICT ON UPDATE RESTRICT
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='来源别名的采集策略稳定身份';

    CREATE TABLE IF NOT EXISTS `biz_collection_policy_version` (
        `version_id` VARCHAR(32) NOT NULL COMMENT '策略版本ID',
        `policy_id` VARCHAR(32) NOT NULL COMMENT '所属策略',
        `version_no` INT NOT NULL COMMENT '单调递增版本号',
        `status` VARCHAR(20) NOT NULL COMMENT 'DRAFT、ACTIVE、RETIRED',
        `enabled_flag` TINYINT(1) NOT NULL COMMENT '是否期待周期上报',
        `expected_interval_seconds` INT NOT NULL COMMENT '期望采样周期秒数',
        `allowed_delay_seconds` INT NOT NULL COMMENT '允许迟到秒数',
        `time_semantics` VARCHAR(32) NOT NULL COMMENT '首版固定DEVICE_EVENT_TIME',
        `raw_retention_mode` VARCHAR(20) NOT NULL COMMENT 'FIXED_DAYS或LONG_TERM',
        `raw_retention_days` INT DEFAULT NULL COMMENT '固定原始事件保留天数',
        `minute_retention_mode` VARCHAR(20) NOT NULL COMMENT 'FIXED_DAYS或LONG_TERM',
        `minute_retention_days` INT DEFAULT NULL COMMENT '固定分钟数据保留天数',
        `source_code_snapshot` VARCHAR(50) NOT NULL COMMENT '发布时数据源编码快照',
        `source_point_code_snapshot` VARCHAR(255) NOT NULL COMMENT '发布时来源点码快照',
        `point_id_snapshot` VARCHAR(32) NOT NULL COMMENT '发布时标准测点ID快照',
        `point_code_snapshot` VARCHAR(100) NOT NULL COMMENT '发布时标准点码快照',
        `data_type_snapshot` VARCHAR(20) DEFAULT NULL COMMENT '发布时数据类型快照',
        `unit_snapshot` VARCHAR(20) DEFAULT NULL COMMENT '发布时单位快照',
        `change_type` VARCHAR(20) NOT NULL COMMENT 'CREATE、UPDATE、DISABLE、ROLLBACK、INITIAL_MIGRATION',
        `change_source` VARCHAR(32) NOT NULL COMMENT 'MANUAL或INITIAL_MIGRATION',
        `change_reason` VARCHAR(500) NOT NULL COMMENT '必填业务变更原因',
        `copied_from_version_id` VARCHAR(32) DEFAULT NULL COMMENT '复制或回滚来源版本',
        `revision` INT NOT NULL DEFAULT 0 COMMENT '草稿并发修订号',
        `created_by` BIGINT DEFAULT NULL COMMENT '创建人；系统迁移为空',
        `published_by` BIGINT DEFAULT NULL COMMENT '发布人；系统迁移为空',
        `published_at` DATETIME(3) DEFAULT NULL,
        `effective_from` DATETIME(3) DEFAULT NULL,
        `effective_to` DATETIME(3) DEFAULT NULL,
        `retired_at` DATETIME(3) DEFAULT NULL,
        `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
        `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
            ON UPDATE CURRENT_TIMESTAMP(3),
        PRIMARY KEY (`version_id`),
        UNIQUE KEY `uk_collection_policy_version_no` (`policy_id`, `version_no`),
        KEY `idx_collection_policy_version_status` (`policy_id`, `status`, `version_no`),
        KEY `idx_collection_policy_version_copied_from` (`copied_from_version_id`),
        CONSTRAINT `chk_collection_policy_version_no` CHECK (`version_no` > 0),
        CONSTRAINT `chk_collection_policy_version_status`
            CHECK (`status` IN ('DRAFT', 'ACTIVE', 'RETIRED')),
        CONSTRAINT `chk_collection_policy_enabled_flag`
            CHECK (`enabled_flag` IN (0, 1)),
        CONSTRAINT `chk_collection_policy_interval`
            CHECK (`expected_interval_seconds` > 0),
        CONSTRAINT `chk_collection_policy_allowed_delay`
            CHECK (`allowed_delay_seconds` >= 0),
        CONSTRAINT `chk_collection_policy_time_semantics`
            CHECK (`time_semantics` = 'DEVICE_EVENT_TIME'),
        CONSTRAINT `chk_collection_policy_raw_retention`
            CHECK ((`raw_retention_mode` = 'FIXED_DAYS' AND `raw_retention_days` > 0)
                OR (`raw_retention_mode` = 'LONG_TERM' AND `raw_retention_days` IS NULL)),
        CONSTRAINT `chk_collection_policy_minute_retention`
            CHECK ((`minute_retention_mode` = 'FIXED_DAYS' AND `minute_retention_days` > 0)
                OR (`minute_retention_mode` = 'LONG_TERM' AND `minute_retention_days` IS NULL)),
        CONSTRAINT `chk_collection_policy_change_type`
            CHECK (`change_type` IN ('CREATE', 'UPDATE', 'DISABLE', 'ROLLBACK', 'INITIAL_MIGRATION')),
        CONSTRAINT `chk_collection_policy_change_source`
            CHECK (`change_source` IN ('MANUAL', 'INITIAL_MIGRATION')),
        CONSTRAINT `chk_collection_policy_change_reason`
            CHECK (CHAR_LENGTH(TRIM(`change_reason`)) > 0),
        CONSTRAINT `chk_collection_policy_revision` CHECK (`revision` >= 0),
        CONSTRAINT `fk_collection_policy_version_policy`
            FOREIGN KEY (`policy_id`) REFERENCES `biz_collection_policy` (`policy_id`)
            ON DELETE RESTRICT ON UPDATE RESTRICT
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集策略不可变发布版本及草稿';

    CREATE TABLE IF NOT EXISTS `biz_collection_review_request` (
        `request_id` VARCHAR(32) NOT NULL COMMENT '审核申请ID',
        `building_id` VARCHAR(32) NOT NULL COMMENT '权限范围',
        `target_type` VARCHAR(32) NOT NULL COMMENT 'SOURCE_ACTIVATION、ALIAS_ACTIVATION、POLICY_VERSION',
        `target_id` VARCHAR(32) NOT NULL COMMENT '数据源、别名或策略版本ID',
        `target_config_revision` INT NOT NULL COMMENT '提交时冻结的修订号',
        `status` VARCHAR(20) NOT NULL COMMENT 'PENDING、APPROVED、REJECTED、WITHDRAWN',
        `submitted_by` BIGINT NOT NULL COMMENT '提交人',
        `submitted_at` DATETIME(3) NOT NULL COMMENT '提交时间',
        `reviewer_id` BIGINT DEFAULT NULL COMMENT '审核平台管理员',
        `review_comment` VARCHAR(500) DEFAULT NULL COMMENT '审核意见',
        `reviewed_at` DATETIME(3) DEFAULT NULL,
        `withdrawn_at` DATETIME(3) DEFAULT NULL,
        `pending_marker` TINYINT(1)
            GENERATED ALWAYS AS (CASE WHEN `status` = 'PENDING' THEN 1 ELSE NULL END) STORED,
        `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
        `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
            ON UPDATE CURRENT_TIMESTAMP(3),
        PRIMARY KEY (`request_id`),
        UNIQUE KEY `uk_collection_review_pending_target`
            (`target_type`, `target_id`, `pending_marker`),
        KEY `idx_collection_review_building_status` (`building_id`, `status`, `submitted_at`),
        KEY `idx_collection_review_submitter` (`submitted_by`, `status`, `submitted_at`),
        CONSTRAINT `chk_collection_review_target_type`
            CHECK (`target_type` IN ('SOURCE_ACTIVATION', 'ALIAS_ACTIVATION', 'POLICY_VERSION')),
        CONSTRAINT `chk_collection_review_status`
            CHECK (`status` IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
        CONSTRAINT `chk_collection_review_revision`
            CHECK (`target_config_revision` >= 0),
        CONSTRAINT `fk_collection_review_building`
            FOREIGN KEY (`building_id`) REFERENCES `building` (`building_id`)
            ON DELETE RESTRICT ON UPDATE RESTRICT
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集配置审核申请';

    CREATE TABLE IF NOT EXISTS `biz_collection_config_audit_log` (
        `audit_id` VARCHAR(32) NOT NULL COMMENT '审计ID',
        `building_id` VARCHAR(32) NOT NULL COMMENT '查询建筑范围',
        `actor_type` VARCHAR(20) NOT NULL COMMENT 'USER或SYSTEM_MIGRATION',
        `operator_id` BIGINT DEFAULT NULL COMMENT '用户操作人；系统迁移为空',
        `action_type` VARCHAR(50) NOT NULL COMMENT '创建、提交、审核、发布、停用、删除或回滚动作',
        `object_type` VARCHAR(50) NOT NULL COMMENT '业务对象类型',
        `object_id` VARCHAR(32) NOT NULL COMMENT '业务对象ID',
        `version_id` VARCHAR(32) DEFAULT NULL COMMENT '关联策略版本，可空',
        `before_summary` VARCHAR(1000) DEFAULT NULL COMMENT '脱敏变更前摘要',
        `after_summary` VARCHAR(1000) DEFAULT NULL COMMENT '脱敏变更后摘要',
        `result` VARCHAR(20) NOT NULL COMMENT '首版只保存SUCCESS',
        `operation_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
        PRIMARY KEY (`audit_id`),
        KEY `idx_collection_audit_building_time` (`building_id`, `operation_time`),
        KEY `idx_collection_audit_object` (`object_type`, `object_id`, `operation_time`),
        KEY `idx_collection_audit_version` (`version_id`, `operation_time`),
        KEY `idx_collection_audit_operator` (`operator_id`, `operation_time`),
        CONSTRAINT `chk_collection_audit_actor_type`
            CHECK (`actor_type` IN ('USER', 'SYSTEM_MIGRATION')),
        CONSTRAINT `chk_collection_audit_result` CHECK (`result` = 'SUCCESS')
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集配置脱敏成功审计';

    -- 新表若已存在但缺失关键字段或唯一约束，不能继续写入候选数据。
    IF (SELECT COUNT(*) FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_collection_policy'
          AND `COLUMN_NAME` IN ('policy_id', 'source_id', 'alias_id', 'building_id',
              'active_version_id', 'draft_version_id', 'create_by', 'create_time', 'update_time')) <> 9
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.`STATISTICS`
           WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_collection_policy'
             AND `INDEX_NAME` = 'uk_collection_policy_alias' AND `NON_UNIQUE` = 0
             AND `SEQ_IN_INDEX` = 1 AND `COLUMN_NAME` = 'alias_id'
       )
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.`KEY_COLUMN_USAGE`
           WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_collection_policy'
             AND `CONSTRAINT_NAME` = 'fk_collection_policy_source_building'
             AND `COLUMN_NAME` = 'source_id'
             AND `REFERENCED_TABLE_NAME` = 'biz_data_source'
       )
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.`KEY_COLUMN_USAGE`
           WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_collection_policy'
             AND `CONSTRAINT_NAME` = 'fk_collection_policy_alias_building'
             AND `COLUMN_NAME` = 'alias_id'
             AND `REFERENCED_TABLE_NAME` = 'biz_point_alias'
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_POLICY_STRUCTURE_CONFLICT';
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_collection_policy_version'
          AND `COLUMN_NAME` IN ('version_id', 'policy_id', 'version_no', 'status', 'enabled_flag',
              'expected_interval_seconds', 'allowed_delay_seconds', 'time_semantics',
              'raw_retention_mode', 'raw_retention_days', 'minute_retention_mode',
              'minute_retention_days', 'source_code_snapshot', 'source_point_code_snapshot',
              'point_id_snapshot', 'point_code_snapshot', 'data_type_snapshot', 'unit_snapshot',
              'change_type', 'change_source', 'change_reason', 'copied_from_version_id', 'revision',
              'created_by', 'published_by', 'published_at', 'effective_from', 'effective_to',
              'retired_at', 'create_time', 'update_time')) <> 31
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.`STATISTICS`
           WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_collection_policy_version'
             AND `INDEX_NAME` = 'uk_collection_policy_version_no' AND `NON_UNIQUE` = 0
             AND `SEQ_IN_INDEX` = 1 AND `COLUMN_NAME` = 'policy_id'
       )
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.`KEY_COLUMN_USAGE`
           WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_collection_policy_version'
             AND `CONSTRAINT_NAME` = 'fk_collection_policy_version_policy'
             AND `COLUMN_NAME` = 'policy_id'
             AND `REFERENCED_TABLE_NAME` = 'biz_collection_policy'
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_POLICY_VERSION_STRUCTURE_CONFLICT';
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_collection_review_request'
          AND `COLUMN_NAME` IN ('request_id', 'building_id', 'target_type', 'target_id',
              'target_config_revision', 'status', 'submitted_by', 'submitted_at', 'reviewer_id',
              'review_comment', 'reviewed_at', 'withdrawn_at', 'pending_marker',
              'create_time', 'update_time')) <> 15
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.`STATISTICS`
           WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_collection_review_request'
             AND `INDEX_NAME` = 'uk_collection_review_pending_target' AND `NON_UNIQUE` = 0
             AND `SEQ_IN_INDEX` = 1 AND `COLUMN_NAME` = 'target_type'
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_REVIEW_STRUCTURE_CONFLICT';
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_collection_config_audit_log'
          AND `COLUMN_NAME` IN ('audit_id', 'building_id', 'actor_type', 'operator_id', 'action_type',
              'object_type', 'object_id', 'version_id', 'before_summary', 'after_summary', 'result',
              'operation_time')) <> 12 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_AUDIT_STRUCTURE_CONFLICT';
    END IF;

    -- 迁移前严格确认冻结来源只属于 BLD001，且完整对应现有 19 个标准测点。
    IF EXISTS (
        SELECT 1 FROM `biz_point_alias`
        WHERE `source_system` = 'MQTT_FREEZE_V1' AND `building_id` <> 'BLD001'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_SOURCE_SYSTEM_BUILDING_CONFLICT';
    END IF;

    SELECT COUNT(*) INTO v_legacy_alias_count
    FROM `biz_point_alias`
    WHERE `building_id` = 'BLD001' AND `source_system` = 'MQTT_FREEZE_V1';

    IF v_legacy_alias_count <> 19 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_LEGACY_ALIAS_COUNT_CONFLICT';
    END IF;

    IF EXISTS (
        SELECT 1 FROM `biz_point_alias`
        WHERE `building_id` = 'BLD001' AND `source_system` = 'MQTT_FREEZE_V1'
          AND `source_point_code` NOT IN (
              'WCR1_TWin', 'WCR1_TWout', 'WCR1_Flow', 'WCR1_PPE', 'WCR1_Voltage',
              'WCR1_Current', 'WCR1_PF', 'TOWER1_TCWin', 'TOWER1_TCWout', 'TOWER1_TWB',
              'PUMP1_Flow', 'PUMP1_Pout', 'PUMP1_Pin', 'PUMP1_Z', 'PUMP1_Power',
              'AHU1_TotalPress', 'AHU1_EtaT', 'DBO_TDB', 'DBO_RH')
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_LEGACY_ALIAS_SET_CONFLICT';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM `biz_point_alias` a
        LEFT JOIN `biz_data_point` p
          ON p.`point_id` = a.`point_id` AND p.`building_id` = a.`building_id`
        WHERE a.`building_id` = 'BLD001' AND a.`source_system` = 'MQTT_FREEZE_V1'
          AND (p.`point_id` IS NULL OR p.`del_flag` <> 0
               OR NOT (
                   (a.`source_point_code` = 'WCR1_TWin' AND p.`point_code` = 'WCR1_TWin')
                OR (a.`source_point_code` = 'WCR1_TWout' AND p.`point_code` = 'WCR1_TWout')
                OR (a.`source_point_code` = 'WCR1_Flow' AND p.`point_code` = 'WCR1_GW')
                OR (a.`source_point_code` = 'WCR1_PPE' AND p.`point_code` = 'WCR1_PPE')
                OR (a.`source_point_code` = 'WCR1_Voltage' AND p.`point_code` = 'WCR1_Voltage')
                OR (a.`source_point_code` = 'WCR1_Current' AND p.`point_code` = 'WCR1_Current')
                OR (a.`source_point_code` = 'WCR1_PF' AND p.`point_code` = 'WCR1_PF')
                OR (a.`source_point_code` = 'TOWER1_TCWin' AND p.`point_code` = 'WCR1_CT_TWin')
                OR (a.`source_point_code` = 'TOWER1_TCWout' AND p.`point_code` = 'WCR1_CT_TWout')
                OR (a.`source_point_code` = 'TOWER1_TWB' AND p.`point_code` = 'WCR1_CT_TWB')
                OR (a.`source_point_code` = 'PUMP1_Flow' AND p.`point_code` = 'WCR1_Pc_GW')
                OR (a.`source_point_code` = 'PUMP1_Pout' AND p.`point_code` = 'WCR1_Pc_Pout')
                OR (a.`source_point_code` = 'PUMP1_Pin' AND p.`point_code` = 'WCR1_Pc_Pin')
                OR (a.`source_point_code` = 'PUMP1_Z' AND p.`point_code` = 'WCR1_Pc_Z')
                OR (a.`source_point_code` = 'PUMP1_Power' AND p.`point_code` = 'WCR1_Pc_PPE')
                OR (a.`source_point_code` = 'AHU1_TotalPress' AND p.`point_code` = 'AHU1_TotalPress')
                OR (a.`source_point_code` = 'AHU1_EtaT' AND p.`point_code` = 'AHU1_EtaT')
                OR (a.`source_point_code` = 'DBO_TDB' AND p.`point_code` = 'DBO')
                OR (a.`source_point_code` = 'DBO_RH' AND p.`point_code` = 'RHO')
               ))
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_LEGACY_ALIAS_REFERENCE_CONFLICT';
    END IF;

    SELECT COUNT(DISTINCT a.`point_id`) INTO v_legacy_point_count
    FROM `biz_point_alias` a
    WHERE a.`building_id` = 'BLD001' AND a.`source_system` = 'MQTT_FREEZE_V1';

    IF v_legacy_point_count <> 19 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_LEGACY_POINT_SET_CONFLICT';
    END IF;

    IF EXISTS (
        SELECT 1 FROM `biz_data_source`
        WHERE (`source_code` = 'MQTT_FREEZE_V1' AND `source_id` <> 'SOURCE_MQTT_FREEZE_V1')
           OR (`source_id` = 'SOURCE_MQTT_FREEZE_V1' AND `source_code` <> 'MQTT_FREEZE_V1')
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_SOURCE_DATA_CONFLICT';
    END IF;

    SELECT COUNT(*) INTO v_source_count
    FROM `biz_data_source`
    WHERE `source_id` = 'SOURCE_MQTT_FREEZE_V1'
      AND `source_code` = 'MQTT_FREEZE_V1';

    IF v_source_count = 1 AND EXISTS (
        SELECT 1 FROM `biz_data_source`
        WHERE `source_id` = 'SOURCE_MQTT_FREEZE_V1'
          AND (`building_id` <> 'BLD001' OR `source_category` <> 'DEVICE_ACCESS'
               OR `transport_type` <> 'MQTT')
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_SOURCE_DATA_CONFLICT';
    END IF;

    IF EXISTS (
        SELECT 1 FROM `biz_point_alias`
        WHERE `building_id` = 'BLD001' AND `source_system` = 'MQTT_FREEZE_V1'
          AND `source_id` IS NOT NULL AND `source_id` <> 'SOURCE_MQTT_FREEZE_V1'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_LEGACY_ALIAS_SOURCE_CONFLICT';
    END IF;

    SELECT COUNT(*) INTO v_policy_count
    FROM `biz_collection_policy` p
    JOIN `biz_point_alias` a ON a.`alias_id` = p.`alias_id`
    WHERE a.`building_id` = 'BLD001' AND a.`source_system` = 'MQTT_FREEZE_V1'
      AND p.`source_id` = 'SOURCE_MQTT_FREEZE_V1';

    IF EXISTS (
        SELECT 1 FROM `biz_collection_policy` p
        JOIN `biz_point_alias` a ON a.`alias_id` = p.`alias_id`
        WHERE a.`building_id` = 'BLD001' AND a.`source_system` = 'MQTT_FREEZE_V1'
          AND p.`source_id` <> 'SOURCE_MQTT_FREEZE_V1'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_POLICY_SOURCE_CONFLICT';
    END IF;

    SELECT COUNT(*) INTO v_initial_version_count
    FROM `biz_collection_policy_version` v
    JOIN `biz_collection_policy` p ON p.`policy_id` = v.`policy_id`
    JOIN `biz_point_alias` a ON a.`alias_id` = p.`alias_id`
    WHERE a.`building_id` = 'BLD001' AND a.`source_system` = 'MQTT_FREEZE_V1'
      AND p.`source_id` = 'SOURCE_MQTT_FREEZE_V1'
      AND v.`version_no` = 1
      AND v.`change_type` = 'INITIAL_MIGRATION'
      AND v.`change_source` = 'INITIAL_MIGRATION'
      AND v.`expected_interval_seconds` = 60
      AND v.`allowed_delay_seconds` = 30
      AND v.`raw_retention_mode` = 'FIXED_DAYS'
      AND v.`raw_retention_days` = 90
      AND v.`minute_retention_mode` = 'LONG_TERM'
      AND v.`minute_retention_days` IS NULL
      AND v.`time_semantics` = 'DEVICE_EVENT_TIME';

    SELECT COUNT(*) INTO v_migration_audit_count
    FROM `biz_collection_config_audit_log` al
    WHERE al.`actor_type` = 'SYSTEM_MIGRATION'
      AND al.`action_type` = 'INITIAL_MIGRATION'
      AND ((al.`object_type` = 'DATA_SOURCE' AND al.`object_id` = 'SOURCE_MQTT_FREEZE_V1')
        OR (al.`object_type` = 'COLLECTION_POLICY'
            AND al.`object_id` IN (
                SELECT UPPER(SUBSTRING(SHA2(CONCAT('COLLECTION_POLICY|', a.`alias_id`), 256), 1, 32))
                FROM `biz_point_alias` a
                WHERE a.`building_id` = 'BLD001' AND a.`source_system` = 'MQTT_FREEZE_V1')));

    -- 已成功迁移后允许后续版本和启停继续演进；只核验初始事实仍可追溯，不覆盖任何数据。
    IF v_source_count = 1
       AND v_policy_count = 19
       AND v_initial_version_count = 19
       AND v_migration_audit_count = 20
       AND NOT EXISTS (
           SELECT 1 FROM `biz_point_alias`
           WHERE `building_id` = 'BLD001' AND `source_system` = 'MQTT_FREEZE_V1'
             AND (`source_id` IS NULL OR `source_id` <> 'SOURCE_MQTT_FREEZE_V1')
       ) THEN
        SELECT 'COLLECTION_MIGRATION_ALREADY_APPLIED' AS `migration_status`;
    ELSEIF v_source_count <> 0 OR v_policy_count <> 0 OR v_initial_version_count <> 0
       OR v_migration_audit_count <> 0
       OR EXISTS (
           SELECT 1 FROM `biz_point_alias`
           WHERE `building_id` = 'BLD001' AND `source_system` = 'MQTT_FREEZE_V1'
             AND `source_id` IS NOT NULL
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_PARTIAL_DATA_CONFLICT';
    ELSE
        IF EXISTS (
            SELECT 1 FROM `biz_point_alias`
            WHERE `building_id` = 'BLD001' AND `source_system` = 'MQTT_FREEZE_V1'
              AND `status` <> 1
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'COLLECTION_MIGRATION_LEGACY_ALIAS_STATUS_CONFLICT';
        END IF;

        START TRANSACTION;

        INSERT INTO `biz_data_source`
        (`source_id`, `source_code`, `source_name`, `building_id`, `source_category`,
         `transport_type`, `status`, `description`, `config_revision`, `runtime_revision`,
         `create_by`, `update_by`)
        VALUES
        ('SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', '冻结版 MQTT HVAC 来源', 'BLD001',
         'DEVICE_ACCESS', 'MQTT', 'ENABLED', '系统迁移纳管现有 HVAC 19 测点来源', 1, 1,
         NULL, NULL);

        UPDATE `biz_point_alias`
        SET `source_id` = 'SOURCE_MQTT_FREEZE_V1',
            `revision` = CASE WHEN `revision` = 0 THEN 1 ELSE `revision` END,
            `update_by` = NULL,
            `update_time` = CURRENT_TIMESTAMP(3)
        WHERE `building_id` = 'BLD001' AND `source_system` = 'MQTT_FREEZE_V1';

        INSERT INTO `biz_collection_policy`
        (`policy_id`, `source_id`, `alias_id`, `building_id`, `active_version_id`,
         `draft_version_id`, `create_by`)
        SELECT UPPER(SUBSTRING(SHA2(CONCAT('COLLECTION_POLICY|', a.`alias_id`), 256), 1, 32)),
               'SOURCE_MQTT_FREEZE_V1', a.`alias_id`, a.`building_id`, NULL, NULL, NULL
        FROM `biz_point_alias` a
        WHERE a.`building_id` = 'BLD001' AND a.`source_system` = 'MQTT_FREEZE_V1';

        INSERT INTO `biz_collection_policy_version`
        (`version_id`, `policy_id`, `version_no`, `status`, `enabled_flag`,
         `expected_interval_seconds`, `allowed_delay_seconds`, `time_semantics`,
         `raw_retention_mode`, `raw_retention_days`, `minute_retention_mode`,
         `minute_retention_days`, `source_code_snapshot`, `source_point_code_snapshot`,
         `point_id_snapshot`, `point_code_snapshot`, `data_type_snapshot`, `unit_snapshot`,
         `change_type`, `change_source`, `change_reason`, `copied_from_version_id`, `revision`,
         `created_by`, `published_by`, `published_at`, `effective_from`, `effective_to`, `retired_at`)
        SELECT UPPER(SUBSTRING(SHA2(CONCAT('COLLECTION_POLICY_VERSION|', a.`alias_id`), 256), 1, 32)),
               UPPER(SUBSTRING(SHA2(CONCAT('COLLECTION_POLICY|', a.`alias_id`), 256), 1, 32)),
               1, 'ACTIVE', 1, 60, 30, 'DEVICE_EVENT_TIME',
               'FIXED_DAYS', 90, 'LONG_TERM', NULL,
               'MQTT_FREEZE_V1', a.`source_point_code`, p.`point_id`, p.`point_code`,
               p.`data_type`, p.`unit`, 'INITIAL_MIGRATION', 'INITIAL_MIGRATION',
               '系统迁移：纳管现有 MQTT_FREEZE_V1 测点', NULL, 1,
               NULL, NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), NULL, NULL
        FROM `biz_point_alias` a
        JOIN `biz_data_point` p
          ON p.`point_id` = a.`point_id` AND p.`building_id` = a.`building_id`
        WHERE a.`building_id` = 'BLD001' AND a.`source_system` = 'MQTT_FREEZE_V1';

        UPDATE `biz_collection_policy` cp
        JOIN `biz_point_alias` a ON a.`alias_id` = cp.`alias_id`
        SET cp.`active_version_id` = UPPER(SUBSTRING(
                SHA2(CONCAT('COLLECTION_POLICY_VERSION|', a.`alias_id`), 256), 1, 32)),
            cp.`update_time` = CURRENT_TIMESTAMP(3)
        WHERE a.`building_id` = 'BLD001' AND a.`source_system` = 'MQTT_FREEZE_V1';

        INSERT INTO `biz_collection_config_audit_log`
        (`audit_id`, `building_id`, `actor_type`, `operator_id`, `action_type`, `object_type`,
         `object_id`, `version_id`, `before_summary`, `after_summary`, `result`)
        VALUES
        (UPPER(SUBSTRING(SHA2('COLLECTION_MIGRATION_AUDIT|SOURCE|SOURCE_MQTT_FREEZE_V1', 256), 1, 32)),
         'BLD001', 'SYSTEM_MIGRATION', NULL, 'INITIAL_MIGRATION', 'DATA_SOURCE',
         'SOURCE_MQTT_FREEZE_V1', NULL, 'legacyAliases=19',
         'status=ENABLED; policies=19; runtimeRevision=1', 'SUCCESS');

        INSERT INTO `biz_collection_config_audit_log`
        (`audit_id`, `building_id`, `actor_type`, `operator_id`, `action_type`, `object_type`,
         `object_id`, `version_id`, `before_summary`, `after_summary`, `result`)
        SELECT UPPER(SUBSTRING(SHA2(CONCAT('COLLECTION_MIGRATION_AUDIT|POLICY|', a.`alias_id`), 256), 1, 32)),
               a.`building_id`, 'SYSTEM_MIGRATION', NULL, 'INITIAL_MIGRATION',
               'COLLECTION_POLICY',
               UPPER(SUBSTRING(SHA2(CONCAT('COLLECTION_POLICY|', a.`alias_id`), 256), 1, 32)),
               UPPER(SUBSTRING(SHA2(CONCAT('COLLECTION_POLICY_VERSION|', a.`alias_id`), 256), 1, 32)),
               'legacyAlias=ENABLED',
               'activeVersion=1; intervalSeconds=60; allowedDelaySeconds=30',
               'SUCCESS'
        FROM `biz_point_alias` a
        WHERE a.`building_id` = 'BLD001' AND a.`source_system` = 'MQTT_FREEZE_V1';

        COMMIT;
        SELECT 'COLLECTION_MIGRATION_APPLIED' AS `migration_status`;
    END IF;
END//
DELIMITER ;

CALL `migrate_collection_policy_governance`();
DROP PROCEDURE `migrate_collection_policy_governance`;
