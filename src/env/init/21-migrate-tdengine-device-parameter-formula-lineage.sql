-- 设备参数受控追溯所需的 TDengine 3.2.3 追加事实。
-- 运行前先 DESCRIBE；应用启动也会只添加缺失字段并创建缺失超级表。
USE `iot_telemetry`;

-- Guard: 仅当 st_formula_calc_attempt_v2 不存在 parameter_evidence_json 时执行。
ALTER STABLE `iot_telemetry`.`st_formula_calc_attempt_v2`
    ADD COLUMN `parameter_evidence_json` NCHAR(4096);

CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_formula_result_revision` (
    `ts`                      TIMESTAMP,
    `result_revision_id`      NCHAR(32),
    `attempt_id`              NCHAR(32),
    `minute_start`            TIMESTAMP,
    `val`                     DOUBLE,
    `data_quality`            TINYINT,
    `formula_version`         NCHAR(32),
    `parameter_evidence_json` NCHAR(4096),
    `calculated_at`           TIMESTAMP
) TAGS (
    `indicator_id`    NCHAR(32),
    `indicator_code`  NCHAR(100),
    `building_id`     NCHAR(32),
    `system_group_id` NCHAR(32),
    `equip_id`        NCHAR(32)
);
