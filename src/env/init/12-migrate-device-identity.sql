-- 本地平台设备身份预注册表。
-- 只保存外部身份到现有设备/建筑/协议模板的绑定，不保存任何遥测值。

CREATE TABLE IF NOT EXISTS `biz_device_identity` (
    `identity_id` VARCHAR(32) NOT NULL COMMENT '平台内部身份绑定ID',
    `identity_type` VARCHAR(20) NOT NULL COMMENT '外部身份类型，如MAC或SERIAL_NO',
    `identity_value` VARCHAR(100) NOT NULL COMMENT '设备上报的外部身份值',
    `equip_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `expected_profile_code` VARCHAR(50) NOT NULL COMMENT '允许的云端协议模板代码',
    `status` TINYINT(1) NOT NULL DEFAULT 1,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`identity_id`),
    UNIQUE KEY `uk_device_identity_value` (`identity_type`, `identity_value`),
    KEY `idx_device_identity_owner` (`equip_id`, `building_id`, `status`),
    CONSTRAINT `fk_device_identity_equipment_building`
        FOREIGN KEY (`equip_id`, `building_id`)
        REFERENCES `biz_equipment` (`equip_id`, `building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备外部身份预注册绑定';

-- 标准多字段别名由“身份类型:身份值:指标代码”组成，原 100 字符不足以覆盖表字段上限。
ALTER TABLE `biz_point_alias`
    MODIFY COLUMN `source_point_code` VARCHAR(255) NOT NULL;
