-- 比对通过后在维护会话显式设置 @building_id、@space_id、@b314_shadow_verified=1。
-- 在来源行锁内检查旧待投递已归零并冻结主键分界。同毫秒的两次观测各自归入唯一历史。
DROP PROCEDURE IF EXISTS cutover_b314_unified;
DELIMITER //
CREATE PROCEDURE cutover_b314_unified(IN building VARCHAR(32), IN room VARCHAR(32), IN verified INT)
BEGIN
    DECLARE radar_source VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    DECLARE door_source VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

    START TRANSACTION;
    SELECT source_id INTO radar_source FROM biz_room_sensor_source
    WHERE identity_id=MD5('room-sensor-identity:AC233FC2B716') FOR UPDATE;
    SELECT source_id INTO door_source FROM biz_room_sensor_source
    WHERE identity_id=MD5('room-sensor-identity:C300008A3BAE') FOR UPDATE;

    IF verified IS NULL OR verified<>1 OR radar_source IS NULL OR door_source IS NULL
      OR (SELECT COUNT(*) FROM biz_room_sensor_source s
        JOIN biz_device_identity i ON i.identity_id=s.identity_id AND i.status=1
        JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.building_id=i.building_id AND e.del_flag=0
        JOIN biz_space r ON r.space_id=e.space_id AND r.building_id=e.building_id AND r.del_flag=0
        JOIN biz_data_point p ON BINARY p.point_id=BINARY s.unified_point_id AND p.equip_id=e.equip_id
            AND p.building_id=e.building_id AND p.del_flag=0 AND p.status='ONLINE'
        WHERE s.source_id IN (radar_source,door_source) AND s.enabled=1
          AND s.first_unified_storage_ts IS NULL AND s.shadow_write=1 AND s.last_storage_ts>0
          AND BINARY e.building_id=BINARY building AND BINARY e.space_id=BINARY room AND r.space_type='ROOM'
          AND (r.space_code='B314' OR r.space_name='B314')
    )<>2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_UNIFIED_CUTOVER_NOT_READY'; END IF;

    IF EXISTS (SELECT 1 FROM biz_room_sensor_ingest_pending WHERE source_id IN (radar_source,door_source))
    THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_UNIFIED_OLD_QUEUE_NOT_DRAINED'; END IF;

    UPDATE biz_room_sensor_source
    SET first_unified_storage_ts=last_storage_ts+1,shadow_write=0
    WHERE source_id IN (radar_source,door_source);
    COMMIT;
END//
DELIMITER ;
CALL cutover_b314_unified(@building_id,@space_id,@b314_shadow_verified);
DROP PROCEDURE cutover_b314_unified;
