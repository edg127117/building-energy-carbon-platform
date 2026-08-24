-- Q0/Q1/Q2 使用策略治理的 MySQL 8 增量迁移。
-- 本脚本只在既有质量事实与消费入口之间建立可审计的使用门禁配置，
-- 不修改 Q0/Q1/Q2 生成、公式、专业阈值、TDengine 既有事实或前端。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

CREATE TABLE IF NOT EXISTS `biz_quality_usage_scenario` (
    `scenario_id` VARCHAR(32) NOT NULL,
    `scenario_code` VARCHAR(64) NOT NULL,
    `scenario_name` VARCHAR(100) NOT NULL,
    `adapter_type` VARCHAR(64) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `introduced_version` VARCHAR(32) NOT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `enabled_at` DATETIME(3) DEFAULT NULL,
    `disabled_at` DATETIME(3) DEFAULT NULL,
    `status_reason` VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (`scenario_id`),
    UNIQUE KEY `uk_quality_usage_scenario_code` (`scenario_code`),
    CONSTRAINT `chk_quality_usage_scenario_status`
        CHECK (`status` IN ('DRAFT','ENABLED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量事实消费场景目录';

CREATE TABLE IF NOT EXISTS `biz_quality_usage_change_set` (
    `change_set_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `revision` INT NOT NULL DEFAULT 0,
    `submitted_revision` INT DEFAULT NULL,
    `created_by` BIGINT NOT NULL,
    `has_been_submitted` TINYINT NOT NULL DEFAULT 0,
    `title` VARCHAR(100) NOT NULL,
    `description` VARCHAR(1000) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `submitted_at` DATETIME(3) DEFAULT NULL,
    `published_at` DATETIME(3) DEFAULT NULL,
    `cancelled_at` DATETIME(3) DEFAULT NULL,
    `last_failure_code` VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (`change_set_id`),
    KEY `idx_quality_usage_change_set_building` (`building_id`,`status`,`create_time`),
    CONSTRAINT `chk_quality_usage_change_set_status`
        CHECK (`status` IN ('DRAFT','PENDING','PUBLISHED','CANCELLED')),
    CONSTRAINT `chk_quality_usage_change_set_revision` CHECK (`revision` >= 0),
    CONSTRAINT `chk_quality_usage_change_set_submitted` CHECK (`has_been_submitted` IN (0,1)),
    CONSTRAINT `fk_quality_usage_change_set_building`
        FOREIGN KEY (`building_id`) REFERENCES `building` (`building_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单建筑质量使用策略变更集';

CREATE TABLE IF NOT EXISTS `biz_quality_usage_policy` (
    `policy_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `scenario_id` VARCHAR(32) NOT NULL,
    `current_active_version_id` VARCHAR(32) DEFAULT NULL,
    `pending_review_request_id` VARCHAR(32) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`policy_id`),
    UNIQUE KEY `uk_quality_usage_policy_identity` (`point_id`,`scenario_id`),
    KEY `idx_quality_usage_policy_building` (`building_id`,`point_id`),
    KEY `idx_quality_usage_policy_pending` (`pending_review_request_id`),
    CONSTRAINT `fk_quality_usage_policy_point_building`
        FOREIGN KEY (`point_id`,`building_id`)
        REFERENCES `biz_data_point` (`point_id`,`building_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_quality_usage_policy_scenario`
        FOREIGN KEY (`scenario_id`) REFERENCES `biz_quality_usage_scenario` (`scenario_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测点与使用场景的稳定策略身份';

CREATE TABLE IF NOT EXISTS `biz_quality_usage_policy_version` (
    `version_id` VARCHAR(32) NOT NULL,
    `policy_id` VARCHAR(32) NOT NULL,
    `change_set_id` VARCHAR(32) DEFAULT NULL,
    `version_no` INT NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `base_active_version_id` VARCHAR(32) DEFAULT NULL,
    `copied_from_version_id` VARCHAR(32) DEFAULT NULL,
    `effective_from_ms` BIGINT DEFAULT NULL,
    `effective_to_ms` BIGINT DEFAULT NULL,
    `initial_baseline` TINYINT NOT NULL DEFAULT 0,
    `published_config_revision` BIGINT DEFAULT NULL,
    `change_source` VARCHAR(40) NOT NULL,
    `change_reason` VARCHAR(500) NOT NULL,
    `created_by` BIGINT DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `published_by` BIGINT DEFAULT NULL,
    `published_at` DATETIME(3) DEFAULT NULL,
    `retired_at` DATETIME(3) DEFAULT NULL,
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`version_id`),
    UNIQUE KEY `uk_quality_usage_version_no` (`policy_id`,`version_no`),
    KEY `idx_quality_usage_version_policy_status` (`policy_id`,`status`,`version_no`),
    KEY `idx_quality_usage_version_change_set` (`change_set_id`,`status`,`version_no`),
    KEY `idx_quality_usage_version_published_revision` (`published_config_revision`),
    CONSTRAINT `chk_quality_usage_version_no` CHECK (`version_no` > 0),
    CONSTRAINT `chk_quality_usage_version_status` CHECK (`status` IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT `chk_quality_usage_initial_baseline` CHECK (`initial_baseline` IN (0,1)),
    CONSTRAINT `chk_quality_usage_effective_range`
        CHECK (`effective_to_ms` IS NULL OR `effective_from_ms` IS NULL OR `effective_to_ms` >= `effective_from_ms`),
    CONSTRAINT `chk_quality_usage_formal_effective_from`
        CHECK (`status` = 'DRAFT' OR `initial_baseline` = 1
            OR (`effective_from_ms` IS NOT NULL AND MOD(`effective_from_ms`, 60000) = 0)),
    CONSTRAINT `chk_quality_usage_effective_to_alignment`
        CHECK (`effective_to_ms` IS NULL OR MOD(`effective_to_ms`, 60000) = 0),
    CONSTRAINT `chk_quality_usage_initial_baseline_shape`
        CHECK (`initial_baseline` = 0
            OR (`version_no` = 1 AND `change_set_id` IS NULL AND `effective_from_ms` IS NULL)),
    CONSTRAINT `fk_quality_usage_version_policy`
        FOREIGN KEY (`policy_id`) REFERENCES `biz_quality_usage_policy` (`policy_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_quality_usage_version_change_set`
        FOREIGN KEY (`change_set_id`) REFERENCES `biz_quality_usage_change_set` (`change_set_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_quality_usage_version_base_active`
        FOREIGN KEY (`base_active_version_id`) REFERENCES `biz_quality_usage_policy_version` (`version_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_quality_usage_version_copied_from`
        FOREIGN KEY (`copied_from_version_id`) REFERENCES `biz_quality_usage_policy_version` (`version_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量使用策略草稿与不可变正式版本';

CREATE TABLE IF NOT EXISTS `biz_quality_usage_policy_level` (
    `policy_level_id` VARCHAR(32) NOT NULL,
    `version_id` VARCHAR(32) NOT NULL,
    `quality_level` VARCHAR(2) NOT NULL,
    PRIMARY KEY (`policy_level_id`),
    UNIQUE KEY `uk_quality_usage_policy_level` (`version_id`,`quality_level`),
    CONSTRAINT `chk_quality_usage_policy_level` CHECK (`quality_level` IN ('Q0','Q1','Q2')),
    CONSTRAINT `fk_quality_usage_policy_level_version`
        FOREIGN KEY (`version_id`) REFERENCES `biz_quality_usage_policy_version` (`version_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量使用策略的显式允许等级集合';

CREATE TABLE IF NOT EXISTS `biz_quality_usage_review_request` (
    `request_id` VARCHAR(32) NOT NULL,
    `change_set_id` VARCHAR(32) NOT NULL,
    `request_no` INT NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `review_mode` VARCHAR(20) NOT NULL,
    `submitted_revision` INT NOT NULL,
    `snapshot_json` JSON NOT NULL,
    `snapshot_sha256` CHAR(64) NOT NULL,
    `submitted_by` BIGINT NOT NULL,
    `submitted_at` DATETIME(3) NOT NULL,
    `reviewer_id` BIGINT DEFAULT NULL,
    `review_comment` VARCHAR(500) DEFAULT NULL,
    `reviewed_at` DATETIME(3) DEFAULT NULL,
    `withdrawn_by` BIGINT DEFAULT NULL,
    `withdrawn_at` DATETIME(3) DEFAULT NULL,
    `idempotency_key` VARCHAR(160) DEFAULT NULL,
    `request_sha256` CHAR(64) DEFAULT NULL,
    `pending_marker` TINYINT GENERATED ALWAYS AS
        (CASE WHEN `status` = 'PENDING' THEN 1 ELSE NULL END) STORED,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`request_id`),
    UNIQUE KEY `uk_quality_usage_review_no` (`change_set_id`,`request_no`),
    UNIQUE KEY `uk_quality_usage_review_idempotency` (`idempotency_key`),
    UNIQUE KEY `uk_quality_usage_review_pending_change_set` (`change_set_id`,`pending_marker`),
    KEY `idx_quality_usage_review_change_set` (`change_set_id`,`status`,`submitted_at`),
    CONSTRAINT `chk_quality_usage_review_status` CHECK (`status` IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
    CONSTRAINT `chk_quality_usage_review_mode` CHECK (`review_mode` IN ('NORMAL','DIRECT_PUBLISH')),
    CONSTRAINT `fk_quality_usage_review_change_set`
        FOREIGN KEY (`change_set_id`) REFERENCES `biz_quality_usage_change_set` (`change_set_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量使用策略审核快照';

CREATE TABLE IF NOT EXISTS `biz_quality_usage_audit_log` (
    `audit_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `actor_type` VARCHAR(20) NOT NULL,
    `operator_id` BIGINT DEFAULT NULL,
    `action_type` VARCHAR(50) NOT NULL,
    `object_type` VARCHAR(50) NOT NULL,
    `object_id` VARCHAR(32) NOT NULL,
    `version_id` VARCHAR(32) DEFAULT NULL,
    `before_summary` VARCHAR(1000) DEFAULT NULL,
    `after_summary` VARCHAR(1000) DEFAULT NULL,
    `result` VARCHAR(20) NOT NULL,
    `reason_code` VARCHAR(64) DEFAULT NULL,
    `config_revision` BIGINT DEFAULT NULL,
    `idempotency_key` VARCHAR(160) DEFAULT NULL,
    `request_sha256` CHAR(64) DEFAULT NULL,
    `operation_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`audit_id`),
    UNIQUE KEY `uk_quality_usage_audit_idempotency` (`idempotency_key`),
    KEY `idx_quality_usage_audit_building_time` (`building_id`,`operation_time`),
    KEY `idx_quality_usage_audit_operator_time` (`operator_id`,`operation_time`),
    CONSTRAINT `chk_quality_usage_audit_actor` CHECK (`actor_type` IN ('USER','SYSTEM_MIGRATION')),
    CONSTRAINT `chk_quality_usage_audit_result` CHECK (`result` IN ('SUCCESS','REJECTED')),
    CONSTRAINT `fk_quality_usage_audit_building`
        FOREIGN KEY (`building_id`) REFERENCES `building` (`building_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量使用策略治理审计';

CREATE TABLE IF NOT EXISTS `biz_quality_usage_config_revision` (
    `singleton_id` TINYINT NOT NULL,
    `config_revision` BIGINT NOT NULL,
    `last_change_summary` VARCHAR(500) DEFAULT NULL,
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`singleton_id`),
    CONSTRAINT `chk_quality_usage_revision_singleton` CHECK (`singleton_id` = 1),
    CONSTRAINT `chk_quality_usage_revision_nonnegative` CHECK (`config_revision` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量使用策略全局修订号';

-- TDengine 追加事实成功但当前状态投影失败时的可恢复、幂等对账任务。
CREATE TABLE IF NOT EXISTS `biz_quality_usage_recovery_task` (
    `task_id` VARCHAR(32) NOT NULL,
    `task_type` VARCHAR(40) NOT NULL,
    `business_key` VARCHAR(200) NOT NULL,
    `payload_json` JSON NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `retry_count` INT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`task_id`),
    UNIQUE KEY `uk_quality_usage_recovery_business_key` (`business_key`),
    KEY `idx_quality_usage_recovery_status` (`status`,`update_time`,`task_id`),
    CONSTRAINT `chk_quality_usage_recovery_status` CHECK (`status` IN ('WAITING','RUNNING','DONE','FAILED')),
    CONSTRAINT `chk_quality_usage_recovery_retry` CHECK (`retry_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量使用策略TDengine投影恢复任务';

-- 首次运行的 CREATE TABLE 定义之外，也允许安全重跑到曾由早期候选脚本创建的表。
-- 指针外键形成 policy/version/review 环，必须在三张表都存在后按名称补齐。
DROP PROCEDURE IF EXISTS `ensure_quality_usage_policy_governance_constraints`;
DELIMITER //
CREATE PROCEDURE `ensure_quality_usage_policy_governance_constraints`()
BEGIN
    DECLARE v_constraint_exists INT DEFAULT 0;

    -- 早期候选曾要求严格大于。正式半开区间允许同一生效分钟的中间审计版本 [t,t)。
    SELECT COUNT(*) INTO v_constraint_exists
    FROM information_schema.`TABLE_CONSTRAINTS`
    WHERE `CONSTRAINT_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'biz_quality_usage_policy_version'
      AND `CONSTRAINT_NAME` = 'chk_quality_usage_effective_range';
    IF v_constraint_exists = 1 THEN
        SET @quality_usage_constraint_sql =
            'ALTER TABLE `biz_quality_usage_policy_version` DROP CHECK `chk_quality_usage_effective_range`';
        PREPARE quality_usage_constraint_statement FROM @quality_usage_constraint_sql;
        EXECUTE quality_usage_constraint_statement;
        DEALLOCATE PREPARE quality_usage_constraint_statement;
    END IF;
    SET @quality_usage_constraint_sql =
        'ALTER TABLE `biz_quality_usage_policy_version` ADD CONSTRAINT `chk_quality_usage_effective_range` '
        'CHECK (`effective_to_ms` IS NULL OR `effective_from_ms` IS NULL '
        'OR `effective_to_ms` >= `effective_from_ms`)';
    PREPARE quality_usage_constraint_statement FROM @quality_usage_constraint_sql;
    EXECUTE quality_usage_constraint_statement;
    DEALLOCATE PREPARE quality_usage_constraint_statement;

    SELECT COUNT(*) INTO v_constraint_exists
    FROM information_schema.`TABLE_CONSTRAINTS`
    WHERE `CONSTRAINT_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'biz_quality_usage_policy'
      AND `CONSTRAINT_NAME` = 'fk_quality_usage_policy_current_active_version';
    IF v_constraint_exists = 0 THEN
        SET @quality_usage_constraint_sql =
            'ALTER TABLE `biz_quality_usage_policy` ADD CONSTRAINT `fk_quality_usage_policy_current_active_version` '
            'FOREIGN KEY (`current_active_version_id`) REFERENCES `biz_quality_usage_policy_version` (`version_id`) '
            'ON DELETE RESTRICT ON UPDATE RESTRICT';
        PREPARE quality_usage_constraint_statement FROM @quality_usage_constraint_sql;
        EXECUTE quality_usage_constraint_statement;
        DEALLOCATE PREPARE quality_usage_constraint_statement;
    END IF;

    SELECT COUNT(*) INTO v_constraint_exists
    FROM information_schema.`TABLE_CONSTRAINTS`
    WHERE `CONSTRAINT_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'biz_quality_usage_policy'
      AND `CONSTRAINT_NAME` = 'fk_quality_usage_policy_pending_review';
    IF v_constraint_exists = 0 THEN
        SET @quality_usage_constraint_sql =
            'ALTER TABLE `biz_quality_usage_policy` ADD CONSTRAINT `fk_quality_usage_policy_pending_review` '
            'FOREIGN KEY (`pending_review_request_id`) REFERENCES `biz_quality_usage_review_request` (`request_id`) '
            'ON DELETE RESTRICT ON UPDATE RESTRICT';
        PREPARE quality_usage_constraint_statement FROM @quality_usage_constraint_sql;
        EXECUTE quality_usage_constraint_statement;
        DEALLOCATE PREPARE quality_usage_constraint_statement;
    END IF;

    SELECT COUNT(*) INTO v_constraint_exists
    FROM information_schema.`TABLE_CONSTRAINTS`
    WHERE `CONSTRAINT_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'biz_quality_usage_policy_version'
      AND `CONSTRAINT_NAME` = 'fk_quality_usage_version_base_active';
    IF v_constraint_exists = 0 THEN
        SET @quality_usage_constraint_sql =
            'ALTER TABLE `biz_quality_usage_policy_version` ADD CONSTRAINT `fk_quality_usage_version_base_active` '
            'FOREIGN KEY (`base_active_version_id`) REFERENCES `biz_quality_usage_policy_version` (`version_id`) '
            'ON DELETE RESTRICT ON UPDATE RESTRICT';
        PREPARE quality_usage_constraint_statement FROM @quality_usage_constraint_sql;
        EXECUTE quality_usage_constraint_statement;
        DEALLOCATE PREPARE quality_usage_constraint_statement;
    END IF;

    SELECT COUNT(*) INTO v_constraint_exists
    FROM information_schema.`TABLE_CONSTRAINTS`
    WHERE `CONSTRAINT_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'biz_quality_usage_policy_version'
      AND `CONSTRAINT_NAME` = 'fk_quality_usage_version_copied_from';
    IF v_constraint_exists = 0 THEN
        SET @quality_usage_constraint_sql =
            'ALTER TABLE `biz_quality_usage_policy_version` ADD CONSTRAINT `fk_quality_usage_version_copied_from` '
            'FOREIGN KEY (`copied_from_version_id`) REFERENCES `biz_quality_usage_policy_version` (`version_id`) '
            'ON DELETE RESTRICT ON UPDATE RESTRICT';
        PREPARE quality_usage_constraint_statement FROM @quality_usage_constraint_sql;
        EXECUTE quality_usage_constraint_statement;
        DEALLOCATE PREPARE quality_usage_constraint_statement;
    END IF;

    SELECT COUNT(*) INTO v_constraint_exists
    FROM information_schema.`TABLE_CONSTRAINTS`
    WHERE `CONSTRAINT_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'biz_quality_usage_policy_version'
      AND `CONSTRAINT_NAME` = 'chk_quality_usage_formal_effective_from';
    IF v_constraint_exists = 0 THEN
        SET @quality_usage_constraint_sql =
            'ALTER TABLE `biz_quality_usage_policy_version` ADD CONSTRAINT `chk_quality_usage_formal_effective_from` '
            'CHECK (`status` = ''DRAFT'' OR `initial_baseline` = 1 '
            'OR (`effective_from_ms` IS NOT NULL AND MOD(`effective_from_ms`, 60000) = 0))';
        PREPARE quality_usage_constraint_statement FROM @quality_usage_constraint_sql;
        EXECUTE quality_usage_constraint_statement;
        DEALLOCATE PREPARE quality_usage_constraint_statement;
    END IF;

    SELECT COUNT(*) INTO v_constraint_exists
    FROM information_schema.`TABLE_CONSTRAINTS`
    WHERE `CONSTRAINT_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'biz_quality_usage_policy_version'
      AND `CONSTRAINT_NAME` = 'chk_quality_usage_effective_to_alignment';
    IF v_constraint_exists = 0 THEN
        SET @quality_usage_constraint_sql =
            'ALTER TABLE `biz_quality_usage_policy_version` ADD CONSTRAINT `chk_quality_usage_effective_to_alignment` '
            'CHECK (`effective_to_ms` IS NULL OR MOD(`effective_to_ms`, 60000) = 0)';
        PREPARE quality_usage_constraint_statement FROM @quality_usage_constraint_sql;
        EXECUTE quality_usage_constraint_statement;
        DEALLOCATE PREPARE quality_usage_constraint_statement;
    END IF;

    SELECT COUNT(*) INTO v_constraint_exists
    FROM information_schema.`TABLE_CONSTRAINTS`
    WHERE `CONSTRAINT_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'biz_quality_usage_policy_version'
      AND `CONSTRAINT_NAME` = 'chk_quality_usage_initial_baseline_shape';
    IF v_constraint_exists = 0 THEN
        SET @quality_usage_constraint_sql =
            'ALTER TABLE `biz_quality_usage_policy_version` ADD CONSTRAINT `chk_quality_usage_initial_baseline_shape` '
            'CHECK (`initial_baseline` = 0 OR '
            '(`version_no` = 1 AND `change_set_id` IS NULL AND `effective_from_ms` IS NULL))';
        PREPARE quality_usage_constraint_statement FROM @quality_usage_constraint_sql;
        EXECUTE quality_usage_constraint_statement;
        DEALLOCATE PREPARE quality_usage_constraint_statement;
    END IF;
END //
DELIMITER ;

CALL `ensure_quality_usage_policy_governance_constraints`();
DROP PROCEDURE IF EXISTS `ensure_quality_usage_policy_governance_constraints`;

DROP PROCEDURE IF EXISTS `migrate_quality_usage_policy_governance`;
DELIMITER //
CREATE PROCEDURE `migrate_quality_usage_policy_governance`()
main: BEGIN
    DECLARE v_alias_count INT DEFAULT 0;
    DECLARE v_point_count INT DEFAULT 0;
    DECLARE v_scenario_count INT DEFAULT 0;
    DECLARE v_policy_count INT DEFAULT 0;
    DECLARE v_version_count INT DEFAULT 0;
    DECLARE v_level_count INT DEFAULT 0;
    DECLARE v_expected_level_count INT DEFAULT 0;
    DECLARE v_marker_count INT DEFAULT 0;
    DECLARE v_existing_count INT DEFAULT 0;
    DECLARE v_revision BIGINT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    -- 避免 CREATE TABLE IF NOT EXISTS 在同名但核心列缺失时静默继续。
    IF (SELECT COUNT(*) FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_quality_usage_policy'
          AND `COLUMN_NAME` IN ('policy_id','building_id','point_id','scenario_id',
              'current_active_version_id','pending_review_request_id')) <> 6
       OR (SELECT COUNT(*) FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_quality_usage_policy_version'
          AND `COLUMN_NAME` IN ('version_id','policy_id','change_set_id','version_no','status',
              'base_active_version_id','effective_from_ms','effective_to_ms','initial_baseline',
              'published_config_revision','change_source','change_reason')) <> 12
       OR (SELECT COUNT(*) FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_quality_usage_config_revision'
          AND `COLUMN_NAME` IN ('singleton_id','config_revision','last_change_summary','updated_at')) <> 4 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'QUALITY_USAGE_MIGRATION_STRUCTURE_CONFLICT';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.`TABLES`
                   WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'building')
       OR NOT EXISTS (SELECT 1 FROM information_schema.`TABLES`
                      WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_data_point')
       OR NOT EXISTS (SELECT 1 FROM information_schema.`TABLES`
                      WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_point_alias') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'QUALITY_USAGE_MIGRATION_PREREQUISITE_MISSING';
    END IF;

    START TRANSACTION;

    INSERT INTO `biz_quality_usage_config_revision`
        (`singleton_id`,`config_revision`,`last_change_summary`)
    VALUES (1,0,'QUALITY_USAGE_MIGRATION_PENDING')
    ON DUPLICATE KEY UPDATE `singleton_id` = VALUES(`singleton_id`);

    SELECT COUNT(*) INTO v_marker_count
    FROM `biz_quality_usage_audit_log`
    WHERE `object_type` = 'QUALITY_USAGE_MIGRATION'
      AND `object_id` = 'QUALITY_USAGE_INITIAL_V1'
      AND `action_type` = 'INITIAL_MIGRATION';
    IF v_marker_count > 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'QUALITY_USAGE_MIGRATION_MARKER_CONFLICT';
    END IF;

    -- 已成功迁移时只核验不可变的 19 点×3 场景基线，不把后续发布、场景停用
    -- 或新增测点视为迁移冲突。初始目录允许正常从 ENABLED 变为 DISABLED。
    IF v_marker_count = 1 THEN
        SELECT COUNT(*) INTO v_scenario_count
        FROM `biz_quality_usage_scenario`
        WHERE (`scenario_id` = 'QUS_SCENARIO_REALTIME_V1'
                AND `scenario_code` = 'POINT_REALTIME_VIEW'
                AND `scenario_name` = '测点实时展示'
                AND `adapter_type` = 'POINT_REALTIME_GATE'
                AND `introduced_version` = 'QUALITY_USAGE_V1')
           OR (`scenario_id` = 'QUS_SCENARIO_HISTORY_V1'
                AND `scenario_code` = 'POINT_HISTORY_VIEW'
                AND `scenario_name` = '测点历史与降采样'
                AND `adapter_type` = 'POINT_HISTORY_GATE'
                AND `introduced_version` = 'QUALITY_USAGE_V1')
           OR (`scenario_id` = 'QUS_SCENARIO_INDICATOR_V1'
                AND `scenario_code` = 'INDICATOR_CALCULATION'
                AND `scenario_name` = '指标公式实际输入'
                AND `adapter_type` = 'INDICATOR_INPUT_GATE'
                AND `introduced_version` = 'QUALITY_USAGE_V1');

        SELECT COUNT(*) INTO v_policy_count
        FROM `biz_quality_usage_policy` p
        JOIN `biz_quality_usage_scenario` s ON s.`scenario_id` = p.`scenario_id`
        WHERE p.`building_id` = 'BLD001'
          AND p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
          AND s.`scenario_code` IN ('POINT_REALTIME_VIEW','POINT_HISTORY_VIEW','INDICATOR_CALCULATION')
          AND p.`policy_id` = LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_POLICY|',
              p.`point_id`, '|', s.`scenario_code`), 256), 1, 32));

        SELECT COUNT(*) INTO v_version_count
        FROM `biz_quality_usage_policy_version` v
        JOIN `biz_quality_usage_policy` p ON p.`policy_id` = v.`policy_id`
        JOIN `biz_quality_usage_scenario` s ON s.`scenario_id` = p.`scenario_id`
        WHERE p.`building_id` = 'BLD001'
          AND p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
          AND s.`scenario_code` IN ('POINT_REALTIME_VIEW','POINT_HISTORY_VIEW','INDICATOR_CALCULATION')
          AND p.`policy_id` = LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_POLICY|',
              p.`point_id`, '|', s.`scenario_code`), 256), 1, 32))
          AND v.`version_id` = LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_VERSION|',
              p.`policy_id`, '|1'), 256), 1, 32))
          AND v.`version_no` = 1
          AND v.`initial_baseline` = 1
          AND v.`change_set_id` IS NULL
          AND v.`effective_from_ms` IS NULL
          AND v.`status` IN ('ACTIVE','RETIRED');

        SELECT COUNT(*) INTO v_level_count
        FROM `biz_quality_usage_policy_level` l
        JOIN `biz_quality_usage_policy_version` v ON v.`version_id` = l.`version_id`
        JOIN `biz_quality_usage_policy` p ON p.`policy_id` = v.`policy_id`
        JOIN `biz_quality_usage_scenario` s ON s.`scenario_id` = p.`scenario_id`
        WHERE p.`building_id` = 'BLD001'
          AND p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
          AND s.`scenario_code` IN ('POINT_REALTIME_VIEW','POINT_HISTORY_VIEW','INDICATOR_CALCULATION')
          AND p.`policy_id` = LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_POLICY|',
              p.`point_id`, '|', s.`scenario_code`), 256), 1, 32))
          AND v.`version_id` = LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_VERSION|',
              p.`policy_id`, '|1'), 256), 1, 32))
          AND v.`version_no` = 1
          AND v.`initial_baseline` = 1
          AND v.`change_set_id` IS NULL
          AND v.`effective_from_ms` IS NULL
          AND v.`status` IN ('ACTIVE','RETIRED');

        SELECT COUNT(*) INTO v_expected_level_count
        FROM `biz_quality_usage_policy_level` l
        JOIN `biz_quality_usage_policy_version` v ON v.`version_id` = l.`version_id`
        JOIN `biz_quality_usage_policy` p ON p.`policy_id` = v.`policy_id`
        JOIN `biz_quality_usage_scenario` s ON s.`scenario_id` = p.`scenario_id`
        WHERE p.`building_id` = 'BLD001'
          AND p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
          AND s.`scenario_code` IN ('POINT_REALTIME_VIEW','POINT_HISTORY_VIEW','INDICATOR_CALCULATION')
          AND p.`policy_id` = LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_POLICY|',
              p.`point_id`, '|', s.`scenario_code`), 256), 1, 32))
          AND v.`version_id` = LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_VERSION|',
              p.`policy_id`, '|1'), 256), 1, 32))
          AND v.`version_no` = 1
          AND v.`initial_baseline` = 1
          AND v.`change_set_id` IS NULL
          AND v.`effective_from_ms` IS NULL
          AND v.`status` IN ('ACTIVE','RETIRED')
          AND ((s.`scenario_code` IN ('POINT_REALTIME_VIEW','POINT_HISTORY_VIEW')
                AND l.`quality_level` IN ('Q0','Q1','Q2'))
               OR (s.`scenario_code` = 'INDICATOR_CALCULATION' AND l.`quality_level` = 'Q0'));

        SELECT `config_revision` INTO v_revision FROM `biz_quality_usage_config_revision`
        WHERE `singleton_id` = 1 FOR UPDATE;
        IF v_scenario_count <> 3 OR v_policy_count <> 57 OR v_version_count <> 57
           OR v_level_count <> 133 OR v_expected_level_count <> 133 OR v_revision < 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'QUALITY_USAGE_MIGRATION_EXISTING_CONTENT_CONFLICT';
        END IF;
        COMMIT;
        LEAVE main;
    END IF;

    SELECT COUNT(*) INTO v_alias_count
    FROM `biz_point_alias` a
    JOIN `biz_data_point` p ON p.`point_id` = a.`point_id` AND p.`building_id` = a.`building_id`
    WHERE a.`building_id` = 'BLD001'
      AND a.`source_id` = 'SOURCE_MQTT_FREEZE_V1'
      AND a.`source_system` = 'MQTT_FREEZE_V1'
      AND a.`status` = 1
      AND p.`del_flag` = 0
      AND p.`status` = 'ONLINE'
      AND p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$';
    SELECT COUNT(DISTINCT p.`point_id`) INTO v_point_count
    FROM `biz_point_alias` a
    JOIN `biz_data_point` p ON p.`point_id` = a.`point_id` AND p.`building_id` = a.`building_id`
    WHERE a.`building_id` = 'BLD001'
      AND a.`source_id` = 'SOURCE_MQTT_FREEZE_V1'
      AND a.`source_system` = 'MQTT_FREEZE_V1'
      AND a.`status` = 1
      AND p.`del_flag` = 0
      AND p.`status` = 'ONLINE'
      AND p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$';
    IF v_alias_count <> 19 OR v_point_count <> 19 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'QUALITY_USAGE_MIGRATION_INITIAL_POINTS_CONFLICT';
    END IF;

    SELECT (SELECT COUNT(*) FROM `biz_quality_usage_scenario`)
         + (SELECT COUNT(*) FROM `biz_quality_usage_policy`)
         + (SELECT COUNT(*) FROM `biz_quality_usage_policy_version`)
         + (SELECT COUNT(*) FROM `biz_quality_usage_policy_level`)
         + (SELECT COUNT(*) FROM `biz_quality_usage_change_set`)
         + (SELECT COUNT(*) FROM `biz_quality_usage_review_request`)
         + (SELECT COUNT(*) FROM `biz_quality_usage_audit_log`)
    INTO v_existing_count;
    SELECT `config_revision` INTO v_revision FROM `biz_quality_usage_config_revision`
    WHERE `singleton_id` = 1 FOR UPDATE;
    IF v_existing_count <> 0 OR v_revision <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'QUALITY_USAGE_MIGRATION_PARTIAL_OR_CONFLICTING_CONTENT';
    END IF;

    INSERT INTO `biz_quality_usage_scenario`
        (`scenario_id`,`scenario_code`,`scenario_name`,`adapter_type`,`status`,`introduced_version`,`enabled_at`)
    VALUES
        ('QUS_SCENARIO_REALTIME_V1','POINT_REALTIME_VIEW','测点实时展示','POINT_REALTIME_GATE','ENABLED','QUALITY_USAGE_V1',CURRENT_TIMESTAMP(3)),
        ('QUS_SCENARIO_HISTORY_V1','POINT_HISTORY_VIEW','测点历史与降采样','POINT_HISTORY_GATE','ENABLED','QUALITY_USAGE_V1',CURRENT_TIMESTAMP(3)),
        ('QUS_SCENARIO_INDICATOR_V1','INDICATOR_CALCULATION','指标公式实际输入','INDICATOR_INPUT_GATE','ENABLED','QUALITY_USAGE_V1',CURRENT_TIMESTAMP(3));

    INSERT INTO `biz_quality_usage_policy`
        (`policy_id`,`building_id`,`point_id`,`scenario_id`)
    SELECT LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_POLICY|', p.`point_id`, '|', s.`scenario_code`), 256),1,32)),
           p.`building_id`, p.`point_id`, s.`scenario_id`
    FROM (
        SELECT DISTINCT p.`point_id`, p.`building_id`
        FROM `biz_point_alias` a
        JOIN `biz_data_point` p ON p.`point_id` = a.`point_id` AND p.`building_id` = a.`building_id`
        WHERE a.`building_id` = 'BLD001'
          AND a.`source_id` = 'SOURCE_MQTT_FREEZE_V1'
          AND a.`source_system` = 'MQTT_FREEZE_V1'
          AND a.`status` = 1 AND p.`del_flag` = 0 AND p.`status` = 'ONLINE'
          AND p.`point_id` REGEXP '^POINT0(0[1-9]|1[0-9])$'
    ) p
    CROSS JOIN `biz_quality_usage_scenario` s
    WHERE s.`scenario_code` IN ('POINT_REALTIME_VIEW','POINT_HISTORY_VIEW','INDICATOR_CALCULATION');

    UPDATE `biz_quality_usage_config_revision`
    SET `config_revision` = `config_revision` + 1,
        `last_change_summary` = 'INITIAL_CONSERVATIVE_POLICY;scenarios=3;policies=57';
    SELECT `config_revision` INTO v_revision FROM `biz_quality_usage_config_revision`
    WHERE `singleton_id` = 1 FOR UPDATE;

    INSERT INTO `biz_quality_usage_policy_version`
        (`version_id`,`policy_id`,`change_set_id`,`version_no`,`status`,`base_active_version_id`,
         `effective_from_ms`,`effective_to_ms`,`initial_baseline`,`published_config_revision`,
         `change_source`,`change_reason`,`created_by`,`published_by`,`published_at`)
    SELECT LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_VERSION|', p.`policy_id`, '|1'), 256),1,32)),
           p.`policy_id`, NULL, 1, 'ACTIVE', NULL, NULL, NULL, 1, v_revision,
           'INITIAL_CONSERVATIVE_POLICY', '系统迁移：建立现有19点质量使用初始策略', NULL, NULL, CURRENT_TIMESTAMP(3)
    FROM `biz_quality_usage_policy` p
    JOIN `biz_quality_usage_scenario` s ON s.`scenario_id` = p.`scenario_id`
    WHERE p.`building_id` = 'BLD001'
      AND s.`scenario_code` IN ('POINT_REALTIME_VIEW','POINT_HISTORY_VIEW','INDICATOR_CALCULATION');

    UPDATE `biz_quality_usage_policy` p
    JOIN `biz_quality_usage_policy_version` v ON v.`policy_id` = p.`policy_id`
    SET p.`current_active_version_id` = v.`version_id`
    WHERE p.`building_id` = 'BLD001' AND v.`version_no` = 1 AND v.`status` = 'ACTIVE';

    INSERT INTO `biz_quality_usage_policy_level` (`policy_level_id`,`version_id`,`quality_level`)
    SELECT LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_LEVEL|', v.`version_id`, '|', seed.`quality_level`), 256),1,32)),
           v.`version_id`, seed.`quality_level`
    FROM `biz_quality_usage_policy_version` v
    JOIN `biz_quality_usage_policy` p ON p.`policy_id` = v.`policy_id`
    JOIN `biz_quality_usage_scenario` s ON s.`scenario_id` = p.`scenario_id`
    JOIN (
        SELECT 'POINT_REALTIME_VIEW' AS `scenario_code`, 'Q0' AS `quality_level`
        UNION ALL SELECT 'POINT_REALTIME_VIEW', 'Q1'
        UNION ALL SELECT 'POINT_REALTIME_VIEW', 'Q2'
        UNION ALL SELECT 'POINT_HISTORY_VIEW', 'Q0'
        UNION ALL SELECT 'POINT_HISTORY_VIEW', 'Q1'
        UNION ALL SELECT 'POINT_HISTORY_VIEW', 'Q2'
        UNION ALL SELECT 'INDICATOR_CALCULATION', 'Q0'
    ) seed ON seed.`scenario_code` = s.`scenario_code`
    WHERE p.`building_id` = 'BLD001' AND v.`version_no` = 1 AND v.`status` = 'ACTIVE';

    INSERT INTO `biz_quality_usage_audit_log`
        (`audit_id`,`building_id`,`actor_type`,`operator_id`,`action_type`,`object_type`,`object_id`,
         `version_id`,`before_summary`,`after_summary`,`result`,`config_revision`)
    SELECT LOWER(SUBSTRING(SHA2(CONCAT('QUALITY_USAGE_AUDIT|', p.`policy_id`), 256),1,32)),
           p.`building_id`, 'SYSTEM_MIGRATION', NULL, 'INITIAL_MIGRATION', 'QUALITY_USAGE_POLICY',
           p.`policy_id`, v.`version_id`, 'legacyPoint=MQTT_FREEZE_V1',
           CONCAT('initialBaseline=true;scenario=', s.`scenario_code`), 'SUCCESS', v_revision
    FROM `biz_quality_usage_policy` p
    JOIN `biz_quality_usage_policy_version` v ON v.`policy_id` = p.`policy_id`
    JOIN `biz_quality_usage_scenario` s ON s.`scenario_id` = p.`scenario_id`
    WHERE p.`building_id` = 'BLD001' AND v.`version_no` = 1;

    INSERT INTO `biz_quality_usage_audit_log`
        (`audit_id`,`building_id`,`actor_type`,`operator_id`,`action_type`,`object_type`,`object_id`,
         `before_summary`,`after_summary`,`result`,`config_revision`)
    VALUES
        ('QUS_MIGRATION_MARKER_V1','BLD001','SYSTEM_MIGRATION',NULL,'INITIAL_MIGRATION',
         'QUALITY_USAGE_MIGRATION','QUALITY_USAGE_INITIAL_V1','aliases=19',
         'scenarios=3;policies=57;versions=57;levels=133','SUCCESS',v_revision);

    COMMIT;
END //
DELIMITER ;

CALL `migrate_quality_usage_policy_governance`();
DROP PROCEDURE IF EXISTS `migrate_quality_usage_policy_governance`;
