-- ============================================================================
-- 已有 TDengine 环境的数据质量补全来源列增量迁移
-- TDengine 不支持 ADD COLUMN IF NOT EXISTS。重复执行前必须先运行：
-- DESCRIBE iot_telemetry.st_raw_minute;
-- 仅当结果中不存在 quality_task_id 时才执行下面的 ALTER。
-- ============================================================================
ALTER STABLE `iot_telemetry`.`st_raw_minute`
    ADD COLUMN `quality_task_id` NCHAR(32);
