-- 人工数据质量重算批次的 MySQL 增量迁移。
--
-- 持久化环境按顺序手工执行本脚本。已有 Docker 数据卷不会自动重放 init 脚本，
-- 因此不能把本次变更回填到 migration 08，也不能依赖重建数据库获得字段。

USE `iot_platform`;

CREATE TABLE IF NOT EXISTS `biz_data_quality_recalc_job` (
    `job_id` VARCHAR(32) NOT NULL,
    `idempotency_key` VARCHAR(160) NOT NULL,
    `job_type` VARCHAR(30) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `point_ids_json` JSON NOT NULL,
    `from_minute` DATETIME(3) NOT NULL,
    `to_minute` DATETIME(3) NOT NULL,
    `supersedes_task_id` VARCHAR(32) DEFAULT NULL,
    `reason` VARCHAR(500) NOT NULL,
    `operator_id` BIGINT NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `phase` VARCHAR(20) NOT NULL,
    `cursor_minute` DATETIME(3) NOT NULL,
    `void_target_minutes_json` JSON DEFAULT NULL,
    `q0_count` INT NOT NULL DEFAULT 0,
    `q1_count` INT NOT NULL DEFAULT 0,
    `q2_count` INT NOT NULL DEFAULT 0,
    `missing_count` INT NOT NULL DEFAULT 0,
    `voided_count` INT NOT NULL DEFAULT 0,
    `replaced_count` INT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `started_at` DATETIME(3) DEFAULT NULL,
    `finished_at` DATETIME(3) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`job_id`),
    UNIQUE KEY `uk_recalc_idempotency` (`idempotency_key`),
    KEY `idx_recalc_status_cursor` (`status`, `update_time`, `job_id`),
    KEY `idx_recalc_building_range`
        (`building_id`, `status`, `from_minute`, `to_minute`),
    KEY `idx_recalc_supersedes`
        (`supersedes_task_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工数据质量重算批次';

-- 字段和索引分别查询 information_schema，保证脚本中断后可以安全重新执行。
SET @ddl = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'biz_data_quality_fill_task'
          AND column_name = 'recalc_job_id'),
    'SELECT 1',
    'ALTER TABLE `biz_data_quality_fill_task`
         ADD COLUMN `recalc_job_id` VARCHAR(32) DEFAULT NULL'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'biz_data_quality_fill_task'
          AND index_name = 'idx_fill_recalc_job'),
    'SELECT 1',
    'ALTER TABLE `biz_data_quality_fill_task`
         ADD KEY `idx_fill_recalc_job` (`recalc_job_id`, `task_id`)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
