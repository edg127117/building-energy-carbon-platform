-- 只提供状态接入元数据草稿；不新增现场设备，不启用产品或正式采集。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

INSERT INTO `biz_equipment_type`
(`type_code`,`type_name`,`asset_code_prefix`,`equip_category`,`standard_source`,`status`)
VALUES ('ODU','空调外机','ODU','OUTDOOR_UNIT','DAIKIN_READONLY_V2',1);

INSERT INTO `biz_device_product`
(`product_id`,`product_code`,`product_name`,`manufacturer`,`model`,
 `equipment_type_code`,`expected_profile_code`,`identity_type`,`status`)
VALUES
('PRODUCT_DAIKIN_IN_STATE','DAIKIN_INDOOR_STATE_V2','大金内机状态接入','大金',NULL,
 'IDU','DAIKIN_INDOOR_V2','DAIKIN_UNIT','DRAFT'),
('PRODUCT_DAIKIN_OUT_STATE','DAIKIN_OUTDOOR_STATE_V2','大金外机状态接入','大金',NULL,
 'ODU','DAIKIN_OUTDOOR_V2','DAIKIN_UNIT','DRAFT');

-- 状态产品不插入 biz_product_point_template；以后接温度或电表时使用明确的数值契约。
