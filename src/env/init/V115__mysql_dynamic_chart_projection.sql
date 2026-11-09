-- 图表字段与完整原文分开保存；原结果表和证据接口保持不变。
CREATE TABLE biz_dynamic_electricity_chart_projection (
 result_id CHAR(64) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 grain VARCHAR(8) NOT NULL,
 start_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL,
 source_version CHAR(64) NOT NULL,
 source_kind VARCHAR(32) NOT NULL,
 evidence_hash CHAR(64) NOT NULL,
 chart_json MEDIUMTEXT NOT NULL,
 chart_hash CHAR(64) NOT NULL,
 last_verified_at BIGINT NOT NULL DEFAULT 0,
 integrity_error VARCHAR(64),
 created_at BIGINT NOT NULL,
 KEY idx_dynamic_chart_window(building_id,start_ms,end_ms),
 KEY idx_dynamic_chart_backfill(created_at,result_id),
 KEY idx_dynamic_chart_audit(integrity_error,last_verified_at,result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 损坏状态出现或投影建筑/时间范围迁移时使相关展示范围失效；定期验证成功不产生来源修订。
DELIMITER $$
CREATE TRIGGER trg_dynamic_chart_integrity_revision_ai
AFTER INSERT ON biz_dynamic_electricity_chart_projection
FOR EACH ROW
BEGIN
 IF NEW.integrity_error IS NOT NULL THEN
  CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_RESULT',NEW.start_ms,NEW.end_ms);
 END IF;
END$$
CREATE TRIGGER trg_dynamic_chart_integrity_revision
AFTER UPDATE ON biz_dynamic_electricity_chart_projection
FOR EACH ROW
BEGIN
 IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.start_ms <=> NEW.start_ms) OR NOT(OLD.end_ms <=> NEW.end_ms) THEN
  CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_RESULT',OLD.start_ms,OLD.end_ms);
  CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_RESULT',NEW.start_ms,NEW.end_ms);
 ELSEIF OLD.integrity_error IS NULL AND NEW.integrity_error IS NOT NULL THEN
  CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_RESULT',NEW.start_ms,NEW.end_ms);
 END IF;
END$$
DELIMITER ;
