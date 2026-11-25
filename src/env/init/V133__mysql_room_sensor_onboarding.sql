-- 待接入记录保存从可信主题验证的网关，审批执行时重新校验真实房间归属。
ALTER TABLE biz_pending_device
    ADD COLUMN discovery_gateway_mac CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN discovery_topic VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL;

-- NULL 表示旧写入。0 表示从首个观测开始写入统一链路。
ALTER TABLE biz_room_sensor_source
    ADD COLUMN first_unified_storage_ts BIGINT NULL,
    ADD COLUMN unified_point_id VARCHAR(32) NULL,
    ADD COLUMN shadow_write TINYINT NOT NULL DEFAULT 0,
    ADD UNIQUE KEY uk_room_sensor_unified_point (unified_point_id);

INSERT INTO biz_equipment_type (type_code,type_name,asset_code_prefix,equip_category,standard_source,status)
SELECT 'RADAR_SENSOR','人数雷达','RADAR','SENSOR','ROOM_SENSOR_MVP',1
WHERE NOT EXISTS (SELECT 1 FROM biz_equipment_type WHERE type_code='RADAR_SENSOR');
INSERT INTO biz_equipment_type (type_code,type_name,asset_code_prefix,equip_category,standard_source,status)
SELECT 'DOOR_SENSOR','门磁传感器','DOOR','SENSOR','ROOM_SENSOR_MVP',1
WHERE NOT EXISTS (SELECT 1 FROM biz_equipment_type WHERE type_code='DOOR_SENSOR');

INSERT INTO biz_point_naming_rule
    (rule_id,standard_version,family_code,component_code,code_template,standard_source,status)
VALUES ('RULE_RADAR_SENSOR','ROOM_SENSOR_V1','RADAR_SENSOR','MAIN','RADAR[n]','ROOM_SENSOR_MVP',1),
       ('RULE_DOOR_SENSOR','ROOM_SENSOR_V1','DOOR_SENSOR','MAIN','DOOR[n]','ROOM_SENSOR_MVP',1),
       ('RULE_RADAR_B314','ROOM_SENSOR_V1_B314','RADAR_SENSOR','MAIN','RADAR_B314','ROOM_SENSOR_MVP',1),
       ('RULE_DOOR_B314','ROOM_SENSOR_V1_B314','DOOR_SENSOR','MAIN','DOOR_B314','ROOM_SENSOR_MVP',1);

INSERT INTO biz_device_product
    (product_id,product_code,product_name,equipment_type_code,expected_profile_code,identity_type,status)
VALUES ('PRODUCT_MSR01_RADAR','MSR01_RADAR','人数雷达','RADAR_SENSOR','MSR01_RADAR','MAC','ENABLED'),
       ('PRODUCT_S4_DOOR','S4_DOOR','门磁传感器','DOOR_SENSOR','S4_DOOR','MAC','ENABLED');

INSERT INTO biz_product_point_template
    (template_point_id,product_id,metric_code,point_name_template,suffix_code,unit,data_type,min_value,max_value,for_calc,required_flag)
VALUES ('TPL_MSR01_COUNT','PRODUCT_MSR01_RADAR','targetCount','目标人数','count','人','ANALOG',0,NULL,0,1),
       ('TPL_S4_OPEN','PRODUCT_S4_DOOR','doorOpen','开关状态','open','-','DIGITAL',0,1,0,1);
