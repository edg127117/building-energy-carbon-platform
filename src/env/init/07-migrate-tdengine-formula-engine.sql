-- ============================================================================
-- TDengine 公式引擎结果存储增量迁移（一次性、只增不删）
--
-- 应用启动时 TdengineConfig 会先 DESCRIBE 并只添加缺失字段；本脚本供受控环境
-- 在确认字段缺失后逐条执行，已存在的字段不得重复执行对应 ALTER。
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
