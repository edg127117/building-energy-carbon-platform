-- ============================================================================
-- TDengine 公式引擎结果存储人工一次性迁移（只增不删）
--
-- 本脚本不由 fresh install 执行；fresh install 使用 04-init-tdengine-hvac.sql。
-- 人工执行前必须先 DESCRIBE `iot_telemetry`.`st_indicator_minute`，并且只运行
-- 对应缺失列的 ALTER 语句；已经存在的列不得重复执行 ALTER。
-- 应用自动幂等升级由 TdengineConfig.ensureFields 承担，它会先 DESCRIBE
-- 并仅添加缺失字段。
-- ============================================================================
USE `iot_telemetry`;

-- Guard: DESCRIBE `iot_telemetry`.`st_indicator_minute`;
-- 仅当输出中不存在 data_quality 时执行。
ALTER STABLE `iot_telemetry`.`st_indicator_minute`
    ADD COLUMN `data_quality` TINYINT;

-- Guard: DESCRIBE `iot_telemetry`.`st_indicator_minute`;
-- 仅当输出中不存在 formula_version 时执行。
ALTER STABLE `iot_telemetry`.`st_indicator_minute`
    ADD COLUMN `formula_version` NCHAR(32);

-- Guard: DESCRIBE `iot_telemetry`.`st_indicator_minute`;
-- 仅当输出中不存在 calculated_at 时执行。
ALTER STABLE `iot_telemetry`.`st_indicator_minute`
    ADD COLUMN `calculated_at` TIMESTAMP;

CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_formula_calc_exception` (
    `ts`              TIMESTAMP NOT NULL,
    `calc_status`     NCHAR(32) NOT NULL,
    `reason_code`     NCHAR(64) NOT NULL,
    `missing_inputs`  NCHAR(512),
    `formula_version` NCHAR(32) NOT NULL,
    `calculated_at`   TIMESTAMP NOT NULL
) TAGS (
    `indicator_id`    NCHAR(32) NOT NULL,
    `indicator_code`  NCHAR(100) NOT NULL,
    `building_id`     NCHAR(32) NOT NULL,
    `system_group_id` NCHAR(32),
    `equip_id`        NCHAR(32)
);
