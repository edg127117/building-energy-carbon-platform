-- 可配置设备接入工作包 B：设备产品归属和脱敏操作审计。
-- 既有设备的 product_id 保持 NULL，不批量回填或改变正式数据链。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

ALTER TABLE `biz_equipment`
    ADD COLUMN `product_id` VARCHAR(32) DEFAULT NULL COMMENT '可空的设备产品模板版本' AFTER `space_id`,
    ADD KEY `idx_equipment_product` (`product_id`),
    ADD CONSTRAINT `fk_equipment_product`
        FOREIGN KEY (`product_id`) REFERENCES `biz_device_product` (`product_id`);

CREATE TABLE IF NOT EXISTS `biz_onboarding_audit_log` (
    `audit_id` VARCHAR(32) NOT NULL,
    `operator_id` BIGINT NOT NULL,
    `action_type` VARCHAR(50) NOT NULL,
    `object_type` VARCHAR(50) NOT NULL,
    `object_id` VARCHAR(32) NOT NULL,
    `before_summary` VARCHAR(1000) DEFAULT NULL,
    `after_summary` VARCHAR(1000) DEFAULT NULL,
    `result` VARCHAR(20) NOT NULL,
    `operation_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`audit_id`),
    KEY `idx_onboarding_audit_object` (`object_type`, `object_id`, `operation_time`),
    KEY `idx_onboarding_audit_operator` (`operator_id`, `operation_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备接入管理操作脱敏审计';
