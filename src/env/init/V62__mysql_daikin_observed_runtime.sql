-- 平台观测开机时长：仅累计相邻有效开关观测之间的有界时间，不回填厂家历史。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

CREATE TABLE IF NOT EXISTS `biz_daikin_observed_runtime_day` (
    `identity_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `building_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `mapping_version` INT NOT NULL,
    `day_start_ms` BIGINT NOT NULL,
    `on_ms` BIGINT NOT NULL DEFAULT 0,
    `covered_ms` BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`identity_id`,`building_id`,`mapping_version`,`day_start_ms`),
    KEY `idx_daikin_observed_runtime_retention` (`day_start_ms`),
    CONSTRAINT `chk_daikin_observed_runtime_time` CHECK (`on_ms` >= 0 AND `covered_ms` >= `on_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台有效开关观测形成的每日开机和覆盖时长';
