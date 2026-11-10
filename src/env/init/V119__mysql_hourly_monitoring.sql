-- 独立监测摘要不替代正式小时批次；原始证据、旧年/月读模型保持原有语义。
CREATE TABLE biz_hourly_monitor_revision (
 building_id VARCHAR(32) NOT NULL,
 hour_ms BIGINT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(building_id,hour_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE biz_hourly_monitor_target (
 building_id VARCHAR(32) NOT NULL,
 day_date DATE NOT NULL,
 actor_id BIGINT NOT NULL,
 next_check BIGINT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36),
 lease_until BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(building_id,day_date),
 KEY idx_hourly_monitor_due(next_check,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE biz_hourly_monitor_result (
 building_id VARCHAR(32) NOT NULL,
 hour_ms BIGINT NOT NULL,
 source_version CHAR(64) NOT NULL,
 evidence_id CHAR(64) NOT NULL,
 summary_json MEDIUMTEXT NOT NULL,
 summary_hash CHAR(64) NOT NULL,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY(building_id,hour_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE biz_hourly_monitor_evidence (
 evidence_id CHAR(64) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 hour_ms BIGINT NOT NULL,
 source_version CHAR(64) NOT NULL,
 evidence_json LONGTEXT NOT NULL,
 created_at BIGINT NOT NULL,
 KEY idx_hourly_monitor_evidence_scope(building_id,hour_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER //
-- RESERVING 只存在于登记事务内，随后一定转为 PENDING 或回滚。
-- 在 PENDING 更新时推进水位即可；若 INSERT 提前锁水位，会与写完原始数据时
-- “PENDING_RAW 配额 -> 水位”的顺序相反，百表并发下形成死锁。
DROP TRIGGER tr_display_raw_intent_ai//
CREATE TRIGGER tr_display_raw_intent_ai AFTER INSERT ON biz_telemetry_raw_intent FOR EACH ROW
BEGIN
 IF NEW.storage_state <> 'RESERVING' THEN
  CALL bump_display_raw_electricity(NEW.building_id,NEW.point_id,NEW.event_ms);
 END IF;
END//

-- 旧月份水位照常推进；只有输入变化附加小时水位，结果发布不递归触发自身重算。
DROP PROCEDURE bump_carbon_display_window//
CREATE PROCEDURE bump_carbon_display_window(
 IN p_building VARCHAR(32), IN p_domain VARCHAR(32), IN p_from_ms BIGINT, IN p_to_ms BIGINT)
BEGIN
 DECLARE v_month DATE;
 DECLARE v_last DATE;
 DECLARE v_hour BIGINT;
 DECLARE v_end BIGINT;
 IF p_from_ms IS NULL OR p_to_ms IS NULL THEN
  CALL bump_carbon_display_revision(p_building,p_domain,'*');
 ELSEIF p_from_ms < p_to_ms THEN
  SET v_month=DATE_ADD('1970-01-01',INTERVAL FLOOR(p_from_ms/86400000) DAY);
  SET v_month=STR_TO_DATE(DATE_FORMAT(v_month,'%Y-%m-01'),'%Y-%m-%d');
  SET v_last=DATE_ADD('1970-01-01',INTERVAL FLOOR((p_to_ms-1)/86400000) DAY);
  SET v_last=STR_TO_DATE(DATE_FORMAT(v_last,'%Y-%m-01'),'%Y-%m-%d');
  WHILE v_month<=v_last DO
   CALL bump_carbon_display_revision(p_building,p_domain,CONCAT('U:',DATE_FORMAT(v_month,'%Y-%m')));
   SET v_month=DATE_ADD(v_month,INTERVAL 1 MONTH);
  END WHILE;
  IF p_domain IN ('DYNAMIC_RAW','DYNAMIC_INPUT','DAILY_INPUT') THEN
   -- 整点 RAW 同时是前小时右锚点；整点前六分钟可成为下一小时左锚点。
   SET v_hour=FLOOR((p_from_ms-IF(p_domain='DYNAMIC_RAW',1,0))/3600000)*3600000;
   SET v_end=FLOOR((p_to_ms+360000-1)/3600000)*3600000;
   WHILE v_hour<=v_end DO
    INSERT INTO biz_hourly_monitor_revision(building_id,hour_ms,revision) VALUES(p_building,v_hour,1)
     ON DUPLICATE KEY UPDATE revision=revision+1;
    SET v_hour=v_hour+3600000;
   END WHILE;
  END IF;
 END IF;
END//
DELIMITER ;

-- 小时静态规则使用独立水位；不得推进旧年/月展示 domain。
DELIMITER //
CREATE PROCEDURE bump_hourly_monitor_rule(IN p_building VARCHAR(32))
BEGIN
 CALL bump_carbon_display_revision(COALESCE(NULLIF(p_building,''),'*'),'HOURLY_RULE','*');
END//

-- 因子及其来源版本：建筑因子按建筑失效，全局/区域因子保守按全局失效。
CREATE TRIGGER tr_hourly_rule_factor_identity_ai AFTER INSERT ON biz_carbon_factor FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_factor_identity_au AFTER UPDATE ON biz_carbon_factor FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_factor_identity_ad AFTER DELETE ON biz_carbon_factor FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_factor_ai AFTER INSERT ON biz_carbon_factor_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule(NEW.building_id); END//
CREATE TRIGGER tr_hourly_rule_factor_au AFTER UPDATE ON biz_carbon_factor_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule(OLD.building_id); IF NOT(OLD.building_id <=> NEW.building_id) THEN CALL bump_hourly_monitor_rule(NEW.building_id); END IF; END//
CREATE TRIGGER tr_hourly_rule_factor_ad AFTER DELETE ON biz_carbon_factor_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule(OLD.building_id); END//

CREATE TRIGGER tr_hourly_rule_factor_component_ai AFTER INSERT ON biz_carbon_factor_component FOR EACH ROW
BEGIN
 DECLARE v_building VARCHAR(32);
 SELECT building_id INTO v_building FROM biz_carbon_factor_version WHERE factor_version_id=NEW.factor_version_id;
 CALL bump_hourly_monitor_rule(v_building);
END//
CREATE TRIGGER tr_hourly_rule_factor_component_au AFTER UPDATE ON biz_carbon_factor_component FOR EACH ROW
BEGIN
 DECLARE v_building VARCHAR(32);
 SELECT building_id INTO v_building FROM biz_carbon_factor_version WHERE factor_version_id=OLD.factor_version_id;
 CALL bump_hourly_monitor_rule(v_building);
 IF NOT(OLD.factor_version_id <=> NEW.factor_version_id) THEN
  SELECT building_id INTO v_building FROM biz_carbon_factor_version WHERE factor_version_id=NEW.factor_version_id;
  CALL bump_hourly_monitor_rule(v_building);
 END IF;
END//
CREATE TRIGGER tr_hourly_rule_factor_component_ad AFTER DELETE ON biz_carbon_factor_component FOR EACH ROW
BEGIN
 DECLARE v_building VARCHAR(32);
 SELECT building_id INTO v_building FROM biz_carbon_factor_version WHERE factor_version_id=OLD.factor_version_id;
 CALL bump_hourly_monitor_rule(v_building);
END//

CREATE TRIGGER tr_hourly_rule_factor_source_ai AFTER INSERT ON biz_carbon_factor_source_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_factor_source_au AFTER UPDATE ON biz_carbon_factor_source_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_factor_source_ad AFTER DELETE ON biz_carbon_factor_source_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_factor_source_identity_ai AFTER INSERT ON biz_carbon_factor_source FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_factor_source_identity_au AFTER UPDATE ON biz_carbon_factor_source FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_factor_source_identity_ad AFTER DELETE ON biz_carbon_factor_source FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//

-- 公式、GWP、舍入规则为全局规则；变化只使小时监测来源指纹过期。
CREATE TRIGGER tr_hourly_rule_formula_ai AFTER INSERT ON biz_carbon_formula_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_formula_au AFTER UPDATE ON biz_carbon_formula_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_formula_ad AFTER DELETE ON biz_carbon_formula_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_gwp_ai AFTER INSERT ON biz_carbon_gwp_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_gwp_au AFTER UPDATE ON biz_carbon_gwp_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_gwp_ad AFTER DELETE ON biz_carbon_gwp_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_rounding_ai AFTER INSERT ON biz_carbon_rounding_policy_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_rounding_au AFTER UPDATE ON biz_carbon_rounding_policy_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//
CREATE TRIGGER tr_hourly_rule_rounding_ad AFTER DELETE ON biz_carbon_rounding_policy_version FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule('*'); END//

CREATE TRIGGER tr_hourly_rule_building_ai AFTER INSERT ON building FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule(NEW.building_id); END//
CREATE TRIGGER tr_hourly_rule_building_au AFTER UPDATE ON building FOR EACH ROW
BEGIN
 IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.region_code <=> NEW.region_code)
    OR NOT(OLD.del_flag <=> NEW.del_flag) THEN
  CALL bump_hourly_monitor_rule(OLD.building_id);
  IF NOT(OLD.building_id <=> NEW.building_id) THEN CALL bump_hourly_monitor_rule(NEW.building_id); END IF;
 END IF;
END//
CREATE TRIGGER tr_hourly_rule_building_ad AFTER DELETE ON building FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule(OLD.building_id); END//

-- 累计表事件/更正治理版本按建筑整体失效；普通 RAW 仍只推进命中的小时水位。
CREATE TRIGGER tr_hourly_rule_meter_event_ai AFTER INSERT ON biz_energy_meter_event FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule(NEW.building_id); END//
CREATE TRIGGER tr_hourly_rule_meter_event_au AFTER UPDATE ON biz_energy_meter_event FOR EACH ROW
BEGIN
 CALL bump_hourly_monitor_rule(OLD.building_id);
 IF NOT(OLD.building_id <=> NEW.building_id) THEN CALL bump_hourly_monitor_rule(NEW.building_id); END IF;
END//
CREATE TRIGGER tr_hourly_rule_meter_event_ad AFTER DELETE ON biz_energy_meter_event FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule(OLD.building_id); END//
CREATE TRIGGER tr_hourly_rule_meter_event_version_ai AFTER INSERT ON biz_energy_meter_event_version FOR EACH ROW
BEGIN
 DECLARE v_building VARCHAR(32);
 SELECT building_id INTO v_building FROM biz_energy_meter_event WHERE event_id=NEW.event_id;
 CALL bump_hourly_monitor_rule(v_building);
END//
CREATE TRIGGER tr_hourly_rule_meter_event_version_au AFTER UPDATE ON biz_energy_meter_event_version FOR EACH ROW
BEGIN
 DECLARE v_building VARCHAR(32);
 SELECT building_id INTO v_building FROM biz_energy_meter_event WHERE event_id=OLD.event_id;
 CALL bump_hourly_monitor_rule(v_building);
 IF NOT(OLD.event_id <=> NEW.event_id) THEN
  SELECT building_id INTO v_building FROM biz_energy_meter_event WHERE event_id=NEW.event_id;
  CALL bump_hourly_monitor_rule(v_building);
 END IF;
END//
CREATE TRIGGER tr_hourly_rule_meter_event_version_ad AFTER DELETE ON biz_energy_meter_event_version FOR EACH ROW
BEGIN
 DECLARE v_building VARCHAR(32);
 SELECT building_id INTO v_building FROM biz_energy_meter_event WHERE event_id=OLD.event_id;
 CALL bump_hourly_monitor_rule(v_building);
END//

CREATE TRIGGER tr_hourly_rule_correction_ai AFTER INSERT ON biz_energy_activity_correction FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule(NEW.building_id); END//
CREATE TRIGGER tr_hourly_rule_correction_au AFTER UPDATE ON biz_energy_activity_correction FOR EACH ROW
BEGIN
 CALL bump_hourly_monitor_rule(OLD.building_id);
 IF NOT(OLD.building_id <=> NEW.building_id) THEN CALL bump_hourly_monitor_rule(NEW.building_id); END IF;
END//
CREATE TRIGGER tr_hourly_rule_correction_ad AFTER DELETE ON biz_energy_activity_correction FOR EACH ROW
BEGIN CALL bump_hourly_monitor_rule(OLD.building_id); END//
CREATE TRIGGER tr_hourly_rule_correction_version_ai AFTER INSERT ON biz_energy_activity_correction_version FOR EACH ROW
BEGIN
 DECLARE v_building VARCHAR(32);
 SELECT building_id INTO v_building FROM biz_energy_activity_correction WHERE correction_id=NEW.correction_id;
 CALL bump_hourly_monitor_rule(v_building);
END//
CREATE TRIGGER tr_hourly_rule_correction_version_au AFTER UPDATE ON biz_energy_activity_correction_version FOR EACH ROW
BEGIN
 DECLARE v_building VARCHAR(32);
 SELECT building_id INTO v_building FROM biz_energy_activity_correction WHERE correction_id=OLD.correction_id;
 CALL bump_hourly_monitor_rule(v_building);
 IF NOT(OLD.correction_id <=> NEW.correction_id) THEN
  SELECT building_id INTO v_building FROM biz_energy_activity_correction WHERE correction_id=NEW.correction_id;
  CALL bump_hourly_monitor_rule(v_building);
 END IF;
END//
CREATE TRIGGER tr_hourly_rule_correction_version_ad AFTER DELETE ON biz_energy_activity_correction_version FOR EACH ROW
BEGIN
 DECLARE v_building VARCHAR(32);
 SELECT building_id INTO v_building FROM biz_energy_activity_correction WHERE correction_id=OLD.correction_id;
 CALL bump_hourly_monitor_rule(v_building);
END//
DELIMITER ;
