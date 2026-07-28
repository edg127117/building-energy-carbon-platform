-- ============================================================================
-- TDengine HVAC 时序模型（工业身份版）
-- 新子表以 point_id / indicator_id 命名；业务编码只作为可读标签。
-- ============================================================================
USE `iot_telemetry`;

-- 逐条真实事件。来源字段是普通列，因为同一标准测点可接入不同协议地址，
-- 且运维复审必须能看到每一条数据原本由哪个设备地址、哪个测点地址上报。
CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_raw_event` (
    `ts`                TIMESTAMP COMMENT '设备采集时间',
    `received_time`     TIMESTAMP COMMENT '服务器接收时间',
    `val`               DOUBLE,
    `data_quality`      TINYINT,
    `late_flag`         TINYINT,
    `source_system`     NCHAR(50),
    `source_point_code` NCHAR(100),
    `source_device_id`  NCHAR(50)
) TAGS (
    `point_id`       NCHAR(32),
    `point_code`     NCHAR(100),
    `building_id`    NCHAR(32),
    `system_group_id` NCHAR(32),
    `equip_id`       NCHAR(32),
    `equip_code`     NCHAR(50),
    `family_code`    NCHAR(20),
    `component_code` NCHAR(20),
    `suffix_code`    NCHAR(20),
    `is_for_calc`    TINYINT
);

-- 正式一分钟聚合。COP 公式只消费此冻结结果；逐条原始表用于复审和重建。
CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_raw_minute` (
    `ts`                  TIMESTAMP,
    `val`                 DOUBLE,
    `data_quality`        TINYINT,
    `avg_val`             DOUBLE,
    `min_val`             DOUBLE,
    `max_val`             DOUBLE,
    `sample_count`        INT,
    `first_received_time` TIMESTAMP,
    `last_received_time`  TIMESTAMP,
    `finalized_at`        TIMESTAMP
) TAGS (
    `point_id`       NCHAR(32),
    `point_code`     NCHAR(100),
    `building_id`    NCHAR(32),
    `system_group_id` NCHAR(32),
    `equip_id`       NCHAR(32),
    `equip_code`     NCHAR(50),
    `family_code`    NCHAR(20),
    `component_code` NCHAR(20),
    `suffix_code`    NCHAR(20),
    `is_for_calc`    TINYINT
);

-- 性能指标实例同样按内部 indicator_id 分子表，支持不同建筑重复使用 WCR_COP。
CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_indicator_minute` (
    `ts`              TIMESTAMP,
    `val`             DOUBLE,
    `data_quality`    TINYINT,
    `formula_version` NCHAR(32),
    `calculated_at`   TIMESTAMP
) TAGS (
    `indicator_id`    NCHAR(32),
    `indicator_code`  NCHAR(100),
    `building_id`     NCHAR(32),
    `system_group_id` NCHAR(32),
    `equip_id`        NCHAR(32)
);

CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_formula_calc_exception` (
    `ts`              TIMESTAMP,
    `calc_status`     NCHAR(32),
    `reason_code`     NCHAR(64),
    `missing_inputs`  NCHAR(512),
    `formula_version` NCHAR(32),
    `calculated_at`   TIMESTAMP
) TAGS (
    `indicator_id`    NCHAR(32),
    `indicator_code`  NCHAR(100),
    `building_id`     NCHAR(32),
    `system_group_id` NCHAR(32),
    `equip_id`        NCHAR(32)
);

-- 只有指标子表需要预建；原始/分钟测点子表由应用在首次数据到达时按 point_id 创建。
CREATE TABLE IF NOT EXISTS `iot_telemetry`.`st_indicator_minute_INDICATOR_WCR_COP_B1`
USING `iot_telemetry`.`st_indicator_minute`
TAGS ('INDICATOR_WCR_COP_B1', 'WCR_COP', 'BLD001', 'GROUP001', 'EQUIP_WCR_B1');

CREATE TABLE IF NOT EXISTS `iot_telemetry`.`st_indicator_minute_INDICATOR_TOWER_EFF_B1`
USING `iot_telemetry`.`st_indicator_minute`
TAGS ('INDICATOR_TOWER_EFF_B1', 'TOWER_EFF', 'BLD001', 'GROUP001', 'EQUIP_TOWER_B1');

CREATE TABLE IF NOT EXISTS `iot_telemetry`.`st_indicator_minute_INDICATOR_PUMP_EFF_B1`
USING `iot_telemetry`.`st_indicator_minute`
TAGS ('INDICATOR_PUMP_EFF_B1', 'PUMP_EFF', 'BLD001', 'GROUP001', 'EQUIP_PUMP_B1');

CREATE TABLE IF NOT EXISTS `iot_telemetry`.`st_indicator_minute_INDICATOR_AHU_EFF_B1`
USING `iot_telemetry`.`st_indicator_minute`
TAGS ('INDICATOR_AHU_EFF_B1', 'AHU_POW_EFF', 'BLD001', 'GROUP001', 'EQUIP_AHU_B1');
