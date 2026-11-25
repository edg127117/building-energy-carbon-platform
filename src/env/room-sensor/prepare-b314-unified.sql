-- 在维护会话显式设置 @building_id、@space_id、@mqtt_source_id、@standard_source_system 后执行。
-- 仅准备并启动 B314 比对，不改当前正式写入及历史读取时间界线。
DROP PROCEDURE IF EXISTS prepare_b314_unified;
DELIMITER //
CREATE PROCEDURE prepare_b314_unified(IN building VARCHAR(32), IN room VARCHAR(32),
        IN mqtt_source VARCHAR(32), IN canonical_source VARCHAR(50))
BEGIN
    DECLARE radar_identity VARCHAR(32) DEFAULT MD5('room-sensor-identity:AC233FC2B716');
    DECLARE door_identity VARCHAR(32) DEFAULT MD5('room-sensor-identity:C300008A3BAE');
    DECLARE locked_radar VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    DECLARE locked_door VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

    START TRANSACTION;
    -- 仅转换参数，兼容历史排序规则并保留身份索引上的行锁。
    SELECT source_id INTO locked_radar FROM biz_room_sensor_source WHERE identity_id=BINARY radar_identity FOR UPDATE;
    SELECT source_id INTO locked_door FROM biz_room_sensor_source WHERE identity_id=BINARY door_identity FOR UPDATE;
    IF locked_radar IS NULL OR locked_door IS NULL OR canonical_source IS NULL OR canonical_source='' OR NOT EXISTS (
        SELECT 1 FROM biz_space WHERE BINARY space_id=BINARY room AND BINARY building_id=BINARY building
          AND space_type='ROOM' AND del_flag=0 AND (space_code='B314' OR space_name='B314')
    ) OR NOT EXISTS (
        SELECT 1 FROM biz_data_source WHERE BINARY source_id=BINARY mqtt_source AND BINARY building_id=BINARY building
          AND transport_type='MQTT' AND status='ENABLED'
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_UNIFIED_PREREQUISITES_INVALID'; END IF;

    IF (SELECT COUNT(*) FROM biz_room_sensor_source s
        JOIN biz_device_identity i ON i.identity_id=s.identity_id AND i.status=1 AND i.identity_type='MAC'
                                   AND BINARY i.expected_profile_code=BINARY s.protocol
        JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.building_id=i.building_id AND e.del_flag=0
        WHERE s.source_id IN (locked_radar,locked_door) AND s.enabled=1 AND s.first_unified_storage_ts IS NULL
          AND s.gateway_mac='AC233FC2B716' AND BINARY e.building_id=BINARY building AND BINARY e.space_id=BINARY room
          AND ((BINARY s.identity_id=BINARY radar_identity AND s.protocol='MSR01_RADAR' AND i.identity_value='AC233FC2B716'
                AND e.equip_code='RADAR_B314' AND e.type_code='RADAR_SENSOR'
                AND (e.product_id IS NULL OR e.product_id='PRODUCT_MSR01_RADAR'))
            OR (BINARY s.identity_id=BINARY door_identity AND s.protocol='S4_DOOR' AND i.identity_value='C300008A3BAE'
                AND e.equip_code='DOOR_B314' AND e.type_code='DOOR_SENSOR'
                AND (e.product_id IS NULL OR e.product_id='PRODUCT_S4_DOOR')))
    )<>2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_UNIFIED_BINDING_CONFLICT'; END IF;

    INSERT INTO biz_data_point (point_id,point_code,point_name,building_id,system_group_id,equip_id,
        naming_rule_id,family_code,component_code,suffix_code,data_type,unit,is_for_calc,value_min,value_max,status)
    SELECT MD5(CONCAT('room-sensor-point:',i.identity_id)),CONCAT(e.equip_code,'_',t.suffix_code),t.point_name_template,
        e.building_id,e.system_group_id,e.equip_id,IF(BINARY i.identity_id=BINARY radar_identity,'RULE_RADAR_B314','RULE_DOOR_B314'),
        e.type_code,'MAIN',t.suffix_code,t.data_type,t.unit,0,t.min_value,t.max_value,'ONLINE'
    FROM biz_device_identity i JOIN biz_equipment e ON e.equip_id=i.equip_id
    JOIN biz_device_product pr ON pr.expected_profile_code=i.expected_profile_code AND pr.product_code=i.expected_profile_code
        AND pr.status='ENABLED' AND pr.equipment_type_code=e.type_code
    JOIN biz_product_point_template t ON t.product_id=pr.product_id AND t.status=1 AND t.required_flag=1
        AND t.metric_code=IF(BINARY i.identity_id=BINARY radar_identity,'targetCount','doorOpen') AND t.for_calc=0
    WHERE BINARY i.identity_id IN (BINARY radar_identity,BINARY door_identity)
      AND NOT EXISTS (SELECT 1 FROM biz_data_point p WHERE p.point_id=MD5(CONCAT('room-sensor-point:',i.identity_id)));

    IF (SELECT COUNT(*) FROM biz_data_point p JOIN biz_equipment e ON e.equip_id=p.equip_id
        JOIN biz_device_identity i ON i.equip_id=e.equip_id AND p.point_id=MD5(CONCAT('room-sensor-point:',i.identity_id))
        WHERE BINARY i.identity_id IN (BINARY radar_identity,BINARY door_identity) AND p.building_id=e.building_id
          AND p.del_flag=0 AND p.status='ONLINE' AND p.is_for_calc=0
          AND ((BINARY i.identity_id=BINARY radar_identity AND p.data_type='ANALOG' AND p.unit='人' AND p.point_code='RADAR_B314_count')
            OR (BINARY i.identity_id=BINARY door_identity AND p.data_type='DIGITAL' AND p.unit='-' AND p.point_code='DOOR_B314_open'))
    )<>2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_UNIFIED_POINT_CONFLICT'; END IF;

    INSERT INTO biz_point_alias (alias_id,building_id,source_id,source_system,source_point_code,point_id,status)
    SELECT MD5(CONCAT('room-sensor-alias:',i.identity_id)),i.building_id,mqtt_source,canonical_source,
        CONCAT('MAC:',i.identity_value,':',IF(BINARY i.identity_id=BINARY radar_identity,'targetCount','doorOpen')),
        MD5(CONCAT('room-sensor-point:',i.identity_id)),1
    FROM biz_device_identity i
    WHERE BINARY i.identity_id IN (BINARY radar_identity,BINARY door_identity)
      AND NOT EXISTS (SELECT 1 FROM biz_point_alias a WHERE a.alias_id=MD5(CONCAT('room-sensor-alias:',i.identity_id)));

    IF (SELECT COUNT(*) FROM biz_point_alias a JOIN biz_device_identity i
        ON a.alias_id=MD5(CONCAT('room-sensor-alias:',i.identity_id)) AND a.building_id=i.building_id
        WHERE BINARY i.identity_id IN (BINARY radar_identity,BINARY door_identity) AND a.status=1
          AND BINARY a.source_id=BINARY mqtt_source AND BINARY a.source_system=BINARY canonical_source
          AND a.point_id=MD5(CONCAT('room-sensor-point:',i.identity_id))
          AND a.source_point_code=CONCAT('MAC:',i.identity_value,':',IF(BINARY i.identity_id=BINARY radar_identity,'targetCount','doorOpen'))
    )<>2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_UNIFIED_ALIAS_CONFLICT'; END IF;

    UPDATE biz_equipment e JOIN biz_device_identity i ON i.equip_id=e.equip_id
    SET e.product_id=IF(BINARY i.identity_id=BINARY radar_identity,'PRODUCT_MSR01_RADAR','PRODUCT_S4_DOOR')
    WHERE BINARY i.identity_id IN (BINARY radar_identity,BINARY door_identity);

    UPDATE biz_room_sensor_source
    SET unified_point_id=MD5(CONCAT('room-sensor-point:',identity_id)),shadow_write=1
    WHERE source_id IN (locked_radar,locked_door);
    COMMIT;
END//
DELIMITER ;
CALL prepare_b314_unified(@building_id,@space_id,@mqtt_source_id,@standard_source_system);
DROP PROCEDURE prepare_b314_unified;
