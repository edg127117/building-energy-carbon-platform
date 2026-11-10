-- 来源水位与原始输入指纹共用候选测点集合，不根据名称或上报数值猜测能源类型。
-- 保留全部已确认历史绑定，不按当前时间/最新版本过滤，以免漏掉历史补传和左右锚点。
-- 这是保守的外购电力候选集；计量树选择、缺失判定和总分表去重仍由既有计算执行。
CREATE VIEW biz_dynamic_raw_electricity_point AS
SELECT b.building_id,b.point_id
FROM biz_energy_point_item_binding b
JOIN biz_energy_point_item_binding_version v ON v.binding_id=b.binding_id
JOIN biz_energy_item_version i ON i.version_id=v.energy_item_version_id
JOIN biz_energy_item_version_scope s ON s.version_id=i.version_id
WHERE v.confirmation_status='CONFIRMED'
  AND i.status='APPROVED' AND i.compatible_category='ELECTRICITY'
  AND s.usage_scope='PURCHASED_ELECTRICITY';

DELIMITER //
CREATE PROCEDURE bump_display_raw_electricity(
    IN p_building VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
    IN p_point VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci, IN p_event_ms BIGINT)
BEGIN
    DECLARE v_point VARCHAR(32) DEFAULT NULL;
    -- 使用当前锁定读，不继承写入事务可能早于绑定确认的 RR 快照；配置变更与原始写入串行判定。
    -- 参数沿用能源绑定表的排序规则，避免数据库默认排序规则不同导致异常或改变身份匹配语义。
    SELECT point_id INTO v_point FROM biz_dynamic_raw_electricity_point
      WHERE building_id=p_building AND point_id=p_point LIMIT 1 FOR SHARE;
    IF v_point IS NOT NULL THEN
        CALL bump_carbon_display_window(p_building,'DYNAMIC_RAW',p_event_ms,p_event_ms+1);
    END IF;
END//

DROP TRIGGER tr_display_raw_intent_ai//
DROP TRIGGER tr_display_raw_intent_au//
DROP TRIGGER tr_display_raw_intent_ad//
CREATE TRIGGER tr_display_raw_intent_ai AFTER INSERT ON biz_telemetry_raw_intent FOR EACH ROW
BEGIN CALL bump_display_raw_electricity(NEW.building_id,NEW.point_id,NEW.event_ms); END//
CREATE TRIGGER tr_display_raw_intent_au AFTER UPDATE ON biz_telemetry_raw_intent FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.point_id <=> NEW.point_id) OR NOT(OLD.event_ms <=> NEW.event_ms)
       OR NOT(OLD.payload_hash <=> NEW.payload_hash) OR NOT(OLD.storage_state <=> NEW.storage_state)
       OR NOT(OLD.conflict_state <=> NEW.conflict_state) OR NOT(OLD.conflict_revision <=> NEW.conflict_revision)
       OR NOT(OLD.selected_json <=> NEW.selected_json) OR NOT(OLD.selection_version <=> NEW.selection_version) THEN
        -- 迁移身份和时间时分别检查 OLD/NEW，不能只看更正后的测点而漏掉原电量来源。
        CALL bump_display_raw_electricity(OLD.building_id,OLD.point_id,OLD.event_ms);
        CALL bump_display_raw_electricity(NEW.building_id,NEW.point_id,NEW.event_ms);
    END IF;
END//
CREATE TRIGGER tr_display_raw_intent_ad AFTER DELETE ON biz_telemetry_raw_intent FOR EACH ROW
BEGIN CALL bump_display_raw_electricity(OLD.building_id,OLD.point_id,OLD.event_ms); END//
DELIMITER ;

-- 绑定/目录变更继续通过既有 DYNAMIC_INPUT 水位失效，补录在确认前发生也不会漏更新。
-- 只淘汰旧 RAW 指纹及展示，不改变已审核电量和日静态结果的水位。
CALL bump_carbon_display_revision('*','DYNAMIC_RAW','*');
