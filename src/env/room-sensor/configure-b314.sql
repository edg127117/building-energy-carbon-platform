-- 环境配置入口，独立于通用结构迁移。执行前由受控发布器设置 @building_id、@space_id。
-- 只允许已登记且启用的 B314 房间；不得依据厂家 mark_name 创建空间。
DELIMITER //
CREATE PROCEDURE configure_b314_room_sensors(IN target_building VARBINARY(32), IN target_space VARBINARY(32))
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
    START TRANSACTION;
    IF target_building IS NULL OR target_space IS NULL OR NOT EXISTS (
        SELECT 1 FROM biz_space WHERE space_id=target_space AND building_id=target_building
          AND del_flag=0 AND (space_code='B314' OR space_name='B314')
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_ROOM_BINDING_REQUIRED'; END IF;

    INSERT INTO biz_equipment_type(type_code,type_name,asset_code_prefix,equip_category,standard_source,status)
    SELECT 'RADAR_SENSOR','人数雷达','RADAR','SENSOR','ROOM_SENSOR_MVP',1
    WHERE NOT EXISTS (SELECT 1 FROM biz_equipment_type WHERE type_code='RADAR_SENSOR');
    INSERT INTO biz_equipment_type(type_code,type_name,asset_code_prefix,equip_category,standard_source,status)
    SELECT 'DOOR_SENSOR','门磁传感器','DOOR','SENSOR','ROOM_SENSOR_MVP',1
    WHERE NOT EXISTS (SELECT 1 FROM biz_equipment_type WHERE type_code='DOOR_SENSOR');
    IF (SELECT COUNT(*) FROM biz_equipment_type WHERE type_code IN ('RADAR_SENSOR','DOOR_SENSOR')
        AND equip_category='SENSOR' AND status=1)<>2
    THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_SENSOR_TYPE_CONFLICT'; END IF;

    -- 旧资产表要求系统分组非空；使用独立传感器分组，不挂到空调系统或能源汇总。
    INSERT INTO biz_system_group(system_group_id,system_group_code,building_id,system_type,system_group_name)
    SELECT MD5(CONCAT(target_building,':ROOM_SENSOR')), 'ROOM_SENSOR',target_building,'ROOM_SENSOR','房间环境传感器'
    WHERE NOT EXISTS (SELECT 1 FROM biz_system_group WHERE system_group_id=MD5(CONCAT(target_building,':ROOM_SENSOR')));
    IF NOT EXISTS (SELECT 1 FROM biz_system_group WHERE system_group_id=MD5(CONCAT(target_building,':ROOM_SENSOR'))
        AND building_id=target_building AND system_type='ROOM_SENSOR' AND del_flag=0)
    THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_SENSOR_GROUP_CONFLICT'; END IF;

    INSERT INTO biz_equipment(equip_id,equip_code,equip_name,type_code,equip_category,system_group_id,building_id,space_id)
    SELECT MD5('room-sensor:AC233FC2B716'),'RADAR_B314','B314人数雷达','RADAR_SENSOR','SENSOR',
        MD5(CONCAT(target_building,':ROOM_SENSOR')),target_building,target_space
    WHERE NOT EXISTS (SELECT 1 FROM biz_equipment WHERE equip_id=MD5('room-sensor:AC233FC2B716'));
    INSERT INTO biz_equipment(equip_id,equip_code,equip_name,type_code,equip_category,system_group_id,building_id,space_id)
    SELECT MD5('room-sensor:C300008A3BAE'),'DOOR_B314','B314门磁','DOOR_SENSOR','SENSOR',
        MD5(CONCAT(target_building,':ROOM_SENSOR')),target_building,target_space
    WHERE NOT EXISTS (SELECT 1 FROM biz_equipment WHERE equip_id=MD5('room-sensor:C300008A3BAE'));
    IF (SELECT COUNT(*) FROM biz_equipment WHERE equip_id IN
        (MD5('room-sensor:AC233FC2B716'),MD5('room-sensor:C300008A3BAE')) AND building_id=target_building
        AND space_id=target_space AND equip_category='SENSOR' AND del_flag=0)<>2
    THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_SENSOR_ASSET_CONFLICT'; END IF;

    INSERT INTO biz_device_identity(identity_id,identity_type,identity_value,equip_id,building_id,expected_profile_code,status)
    SELECT MD5('room-sensor-identity:AC233FC2B716'),'MAC','AC233FC2B716',MD5('room-sensor:AC233FC2B716'),target_building,'MSR01_RADAR',1
    WHERE NOT EXISTS (SELECT 1 FROM biz_device_identity WHERE identity_id=MD5('room-sensor-identity:AC233FC2B716'));
    INSERT INTO biz_device_identity(identity_id,identity_type,identity_value,equip_id,building_id,expected_profile_code,status)
    SELECT MD5('room-sensor-identity:C300008A3BAE'),'MAC','C300008A3BAE',MD5('room-sensor:C300008A3BAE'),target_building,'S4_DOOR',1
    WHERE NOT EXISTS (SELECT 1 FROM biz_device_identity WHERE identity_id=MD5('room-sensor-identity:C300008A3BAE'));
    IF NOT EXISTS (SELECT 1 FROM biz_device_identity WHERE identity_id=MD5('room-sensor-identity:AC233FC2B716')
        AND identity_type='MAC' AND identity_value='AC233FC2B716' AND equip_id=MD5('room-sensor:AC233FC2B716')
        AND building_id=target_building AND expected_profile_code='MSR01_RADAR' AND status=1)
    OR NOT EXISTS (SELECT 1 FROM biz_device_identity WHERE identity_id=MD5('room-sensor-identity:C300008A3BAE')
        AND identity_type='MAC' AND identity_value='C300008A3BAE' AND equip_id=MD5('room-sensor:C300008A3BAE')
        AND building_id=target_building AND expected_profile_code='S4_DOOR' AND status=1)
    THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_SENSOR_IDENTITY_CONFLICT'; END IF;

    INSERT INTO biz_room_sensor_source(source_id,identity_id,gateway_mac,topic,protocol,enabled)
    SELECT MD5('room-sensor-source:AC233FC2B716'),MD5('room-sensor-identity:AC233FC2B716'),
        'AC233FC2B716','/MSR01/ac233fc2b716/radar','MSR01_RADAR',1
    WHERE NOT EXISTS (SELECT 1 FROM biz_room_sensor_source WHERE source_id=MD5('room-sensor-source:AC233FC2B716'));
    INSERT INTO biz_room_sensor_source(source_id,identity_id,gateway_mac,topic,protocol,enabled)
    SELECT MD5('room-sensor-source:C300008A3BAE'),MD5('room-sensor-identity:C300008A3BAE'),
        'AC233FC2B716','/MSR01/ac233fc2b716/status','S4_DOOR',1
    WHERE NOT EXISTS (SELECT 1 FROM biz_room_sensor_source WHERE source_id=MD5('room-sensor-source:C300008A3BAE'));
    IF (SELECT COUNT(*) FROM biz_room_sensor_source WHERE
        (source_id=MD5('room-sensor-source:AC233FC2B716') AND identity_id=MD5('room-sensor-identity:AC233FC2B716')
          AND protocol='MSR01_RADAR' AND topic='/MSR01/ac233fc2b716/radar' AND gateway_mac='AC233FC2B716' AND enabled=1)
        OR (source_id=MD5('room-sensor-source:C300008A3BAE') AND identity_id=MD5('room-sensor-identity:C300008A3BAE')
          AND protocol='S4_DOOR' AND topic='/MSR01/ac233fc2b716/status' AND gateway_mac='AC233FC2B716' AND enabled=1))<>2
    THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B314_SENSOR_SOURCE_CONFLICT'; END IF;
    COMMIT;
END//
DELIMITER ;
CALL configure_b314_room_sensors(@building_id,@space_id);
DROP PROCEDURE configure_b314_room_sensors;
