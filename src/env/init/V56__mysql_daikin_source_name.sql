SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

ALTER TABLE `biz_daikin_source`
    ADD COLUMN `source_name` VARCHAR(200) NULL COMMENT '面向运维人员的数据源名称' AFTER `source_id`;

UPDATE `biz_daikin_source`
SET `source_name` = '大金空调数据源'
WHERE `source_name` IS NULL OR `source_name` = '';

ALTER TABLE `biz_daikin_source`
    MODIFY COLUMN `source_name` VARCHAR(200) NOT NULL COMMENT '面向运维人员的数据源名称';
