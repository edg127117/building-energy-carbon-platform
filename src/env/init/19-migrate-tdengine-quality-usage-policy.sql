-- Q0/Q1/Q2 使用策略执行闭环的 TDengine 3.2.3 增量结构。
-- 成功指标事实继续保留；attempt 为追加事实，state 只保存当前投影。
USE `iot_telemetry`;

CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_formula_calc_attempt_v2` (
    `ts`                   TIMESTAMP,
    `attempt_id`           NCHAR(32),
    `minute_start`         TIMESTAMP,
    `calc_status`          NCHAR(40),
    `reason_code`          NCHAR(64),
    `scenario_code`        NCHAR(64),
    `formula_version`      NCHAR(32),
    `policy_evidence_json` NCHAR(2048),
    `config_revision`      BIGINT
) TAGS (
    `indicator_id`    NCHAR(32),
    `indicator_code`  NCHAR(100),
    `building_id`     NCHAR(32),
    `system_group_id` NCHAR(32),
    `equip_id`        NCHAR(32)
);

CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_indicator_minute_state` (
    `ts`               TIMESTAMP,
    `current_status`   NCHAR(40),
    `source_fact_id`   NCHAR(64),
    `attempt_id`       NCHAR(32),
    `state_updated_at` TIMESTAMP,
    `config_revision`  BIGINT
) TAGS (
    `indicator_id`    NCHAR(32),
    `indicator_code`  NCHAR(100),
    `building_id`     NCHAR(32),
    `system_group_id` NCHAR(32),
    `equip_id`        NCHAR(32)
);
