-- 原始累计量保留独立的事务水位，只有引用 RAW_CUMULATIVE 的结果读取该水位。
-- 不改写原始事实；实时非引用输入不再持续撤销已审核电量的年、月、日展示。
DROP TRIGGER tr_display_raw_intent_ai;
DROP TRIGGER tr_display_raw_intent_au;
DROP TRIGGER tr_display_raw_intent_ad;

DELIMITER //
CREATE TRIGGER tr_display_raw_intent_ai AFTER INSERT ON biz_telemetry_raw_intent FOR EACH ROW
BEGIN CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_RAW',NEW.event_ms,NEW.event_ms+1); END//
CREATE TRIGGER tr_display_raw_intent_au AFTER UPDATE ON biz_telemetry_raw_intent FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.point_id <=> NEW.point_id) OR NOT(OLD.event_ms <=> NEW.event_ms)
       OR NOT(OLD.payload_hash <=> NEW.payload_hash) OR NOT(OLD.storage_state <=> NEW.storage_state)
       OR NOT(OLD.conflict_state <=> NEW.conflict_state) OR NOT(OLD.conflict_revision <=> NEW.conflict_revision)
       OR NOT(OLD.selected_json <=> NEW.selected_json) OR NOT(OLD.selection_version <=> NEW.selection_version) THEN
        CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_RAW',OLD.event_ms,OLD.event_ms+1);
        CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_RAW',NEW.event_ms,NEW.event_ms+1);
    END IF;
END//
CREATE TRIGGER tr_display_raw_intent_ad AFTER DELETE ON biz_telemetry_raw_intent FOR EACH ROW
BEGIN CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_RAW',OLD.event_ms,OLD.event_ms+1); END//
DELIMITER ;

-- 原始累计量的候选和已核对任务必须使用新域；不撤销未引用原始累计量的审核电量结果。
CALL bump_carbon_display_revision('*','DYNAMIC_RAW','*');
