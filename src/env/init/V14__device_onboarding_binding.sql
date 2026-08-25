-- 可配置设备接入工作包 B：设备产品归属和脱敏操作审计。
-- 既有设备的 product_id 保持 NULL，不批量回填或改变正式数据链。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

-- MySQL DDL 会隐式提交，因此逐项检查并补齐列、索引和外键，支持中断后安全重跑。
DROP PROCEDURE IF EXISTS `migrate_device_onboarding_binding`;
DELIMITER //
CREATE PROCEDURE `migrate_device_onboarding_binding`()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_equipment'
          AND `COLUMN_NAME`='product_id'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.`COLUMNS`
            WHERE `TABLE_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_equipment'
              AND `COLUMN_NAME`='product_id'
              AND (LOWER(`COLUMN_TYPE`)<>'varchar(32)' OR `IS_NULLABLE`<>'YES')
        ) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ONBOARDING_PRODUCT_COLUMN_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_equipment`
            ADD COLUMN `product_id` VARCHAR(32) DEFAULT NULL
                COMMENT '可空的设备产品模板版本' AFTER `space_id`;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`STATISTICS`
        WHERE `TABLE_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_equipment'
          AND `INDEX_NAME`='idx_equipment_product'
    ) THEN
        IF (SELECT COUNT(*) FROM information_schema.`STATISTICS`
            WHERE `TABLE_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_equipment'
              AND `INDEX_NAME`='idx_equipment_product')<>1
           OR NOT EXISTS (
               SELECT 1 FROM information_schema.`STATISTICS`
               WHERE `TABLE_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_equipment'
                 AND `INDEX_NAME`='idx_equipment_product'
                 AND `SEQ_IN_INDEX`=1 AND `COLUMN_NAME`='product_id'
           ) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ONBOARDING_PRODUCT_INDEX_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_equipment`
            ADD KEY `idx_equipment_product` (`product_id`);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.`KEY_COLUMN_USAGE`
        WHERE `CONSTRAINT_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_equipment'
          AND `CONSTRAINT_NAME`='fk_equipment_product'
    ) THEN
        IF (SELECT COUNT(*) FROM information_schema.`KEY_COLUMN_USAGE`
            WHERE `CONSTRAINT_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_equipment'
              AND `CONSTRAINT_NAME`='fk_equipment_product')<>1
           OR NOT EXISTS (
               SELECT 1 FROM information_schema.`KEY_COLUMN_USAGE`
               WHERE `CONSTRAINT_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_equipment'
                 AND `CONSTRAINT_NAME`='fk_equipment_product'
                 AND `COLUMN_NAME`='product_id'
                 AND `REFERENCED_TABLE_SCHEMA`=DATABASE()
                 AND `REFERENCED_TABLE_NAME`='biz_device_product'
                 AND `REFERENCED_COLUMN_NAME`='product_id'
           ) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ONBOARDING_PRODUCT_FK_CONFLICT';
        END IF;
    ELSE
        ALTER TABLE `biz_equipment`
            ADD CONSTRAINT `fk_equipment_product`
                FOREIGN KEY (`product_id`) REFERENCES `biz_device_product` (`product_id`);
    END IF;
END//
DELIMITER ;

CALL `migrate_device_onboarding_binding`();
DROP PROCEDURE `migrate_device_onboarding_binding`;

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
