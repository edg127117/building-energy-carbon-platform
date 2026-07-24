-- ============================================================================
-- TDengine 旧 HVAC 超级表 -> 工业身份扩展（一次性、只增不删）
--
-- 重要：TDengine 与 MySQL 不能跨库 JOIN，因此本文件先扩展超级表结构。
-- 旧子表不删除、不改名；迁移后应用只向 point_id / indicator_id 子表写新数据。
-- 历史子表复制命令需按文件末尾说明，由 MySQL 身份映射导出后执行。
-- ============================================================================
USE `iot_telemetry`;

-- 原始事件新增每行都可能变化的来源证据列。
ALTER STABLE `iot_telemetry`.`st_raw_event`
    ADD COLUMN `source_system` NCHAR(50);
ALTER STABLE `iot_telemetry`.`st_raw_event`
    ADD COLUMN `source_point_code` NCHAR(100);
ALTER STABLE `iot_telemetry`.`st_raw_event`
    ADD COLUMN `source_device_id` NCHAR(50);

-- 原始事件新增稳定的内部身份和语义标签。
ALTER STABLE `iot_telemetry`.`st_raw_event`
    ADD TAG `point_id` NCHAR(32);
ALTER STABLE `iot_telemetry`.`st_raw_event`
    ADD TAG `system_group_id` NCHAR(32);
ALTER STABLE `iot_telemetry`.`st_raw_event`
    ADD TAG `equip_code` NCHAR(50);
ALTER STABLE `iot_telemetry`.`st_raw_event`
    ADD TAG `family_code` NCHAR(20);
ALTER STABLE `iot_telemetry`.`st_raw_event`
    ADD TAG `component_code` NCHAR(20);

-- 分钟表采用相同的稳定身份标签，恢复任务才能按 point_id 判断缺口。
ALTER STABLE `iot_telemetry`.`st_raw_minute`
    ADD TAG `point_id` NCHAR(32);
ALTER STABLE `iot_telemetry`.`st_raw_minute`
    ADD TAG `system_group_id` NCHAR(32);
ALTER STABLE `iot_telemetry`.`st_raw_minute`
    ADD TAG `equip_code` NCHAR(50);
ALTER STABLE `iot_telemetry`.`st_raw_minute`
    ADD TAG `family_code` NCHAR(20);
ALTER STABLE `iot_telemetry`.`st_raw_minute`
    ADD TAG `component_code` NCHAR(20);

-- 指标编码可以跨建筑重复，新增 indicator_id 作为真正子表身份。
ALTER STABLE `iot_telemetry`.`st_indicator_minute`
    ADD TAG `indicator_id` NCHAR(32);

-- ============================================================================
-- 历史数据迁移说明
-- ============================================================================
-- 1. 在执行完 05 MySQL 迁移后，从 MySQL 导出：
--
-- SELECT
--   m.old_point_code,
--   m.new_point_id,
--   p.point_code,
--   p.building_id,
--   p.system_group_id,
--   p.equip_id,
--   e.equip_code,
--   p.family_code,
--   p.component_code,
--   p.suffix_code,
--   p.is_for_calc
-- FROM migration_point_id_map m
-- JOIN biz_data_point p ON p.point_id=m.new_point_id
-- LEFT JOIN biz_equipment e ON e.equip_id=p.equip_id;
--
-- 2. 对每一行在 TDengine 执行以下模板。旧表继续保留，因此复制可复核、可重跑：
--
-- CREATE TABLE IF NOT EXISTS `iot_telemetry`.`st_raw_minute_<new_point_id>`
-- USING `iot_telemetry`.`st_raw_minute`
-- (point_code,building_id,equip_id,suffix_code,is_for_calc,
--  point_id,system_group_id,equip_code,family_code,component_code)
-- TAGS ('<canonical_point_code>','<building_id>','<equip_id>','<suffix_code>',<is_for_calc>,
--       '<new_point_id>','<system_group_id>','<equip_code>','<family_code>','<component_code>');
--
-- INSERT INTO `iot_telemetry`.`st_raw_minute_<new_point_id>`
-- (ts,val,data_quality,avg_val,min_val,max_val,sample_count,
--  first_received_time,last_received_time,finalized_at)
-- SELECT ts,val,data_quality,avg_val,min_val,max_val,sample_count,
--        first_received_time,last_received_time,finalized_at
-- FROM `iot_telemetry`.`st_raw_minute_<old_point_code>`;
--
-- 对存在逐条历史数据的测点，再执行：
--
-- CREATE TABLE IF NOT EXISTS `iot_telemetry`.`st_raw_event_<new_point_id>`
-- USING `iot_telemetry`.`st_raw_event`
-- (point_code,building_id,equip_id,suffix_code,is_for_calc,
--  point_id,system_group_id,equip_code,family_code,component_code)
-- TAGS ('<canonical_point_code>','<building_id>','<equip_id>','<suffix_code>',<is_for_calc>,
--       '<new_point_id>','<system_group_id>','<equip_code>','<family_code>','<component_code>');
--
-- INSERT INTO `iot_telemetry`.`st_raw_event_<new_point_id>`
-- (ts,received_time,val,data_quality,late_flag,
--  source_system,source_point_code,source_device_id)
-- SELECT ts,received_time,val,data_quality,late_flag,
--        'MQTT_FREEZE_V1','<old_point_code>','<legacy_equip_code>'
-- FROM `iot_telemetry`.`st_raw_event_<old_point_code>`;
--
-- 3. 对比新旧子表 COUNT(*) 和 MIN/MAX(ts) 后再恢复应用。
-- 4. 本脚本永远不执行 DROP；旧子表的最终归档/清理由变更单单独审批。
