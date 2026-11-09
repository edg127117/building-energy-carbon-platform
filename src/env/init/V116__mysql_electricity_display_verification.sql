-- 后台检查水位可恢复，原始计算结果与证据正文保持不变。
CREATE TABLE biz_dynamic_input_check (
 building_id VARCHAR(32) NOT NULL,
 start_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL,
 task_revision BIGINT NOT NULL,
 metadata_version VARCHAR(64) NOT NULL,
 source_version CHAR(64) NOT NULL,
 PRIMARY KEY(building_id,start_ms,end_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE biz_daily_evidence_verification (
 evidence_kind VARCHAR(8) NOT NULL,
 batch_id VARCHAR(32) NOT NULL,
 building_id VARCHAR(32) NOT NULL,
 day_date DATE NOT NULL,
 last_verified_at BIGINT NOT NULL DEFAULT 0,
 integrity_error VARCHAR(64) NULL,
 PRIMARY KEY(evidence_kind,batch_id),
 KEY idx_daily_evidence_verify(last_verified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$
CREATE TRIGGER trg_daily_evidence_verify_insert AFTER INSERT ON biz_daily_evidence_verification FOR EACH ROW
BEGIN
 IF NEW.integrity_error IS NOT NULL THEN
  CALL bump_carbon_display_local_month(NEW.building_id,'DAILY_RESULT',NEW.day_date);
 END IF;
END$$
CREATE TRIGGER trg_daily_evidence_verify_update AFTER UPDATE ON biz_daily_evidence_verification FOR EACH ROW
BEGIN
 IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.day_date <=> NEW.day_date) THEN
  CALL bump_carbon_display_local_month(OLD.building_id,'DAILY_RESULT',OLD.day_date);
  CALL bump_carbon_display_local_month(NEW.building_id,'DAILY_RESULT',NEW.day_date);
 ELSEIF NOT (OLD.integrity_error <=> NEW.integrity_error) THEN
  CALL bump_carbon_display_local_month(NEW.building_id,'DAILY_RESULT',NEW.day_date);
 END IF;
END$$
DELIMITER ;
