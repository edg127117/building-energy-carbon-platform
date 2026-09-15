-- 大金只读目录仅登记稳定来源、项目建筑映射和待接入扩展，不保存厂家凭据。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

CREATE TABLE IF NOT EXISTS `biz_daikin_source` (
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL COMMENT '稳定来源身份，不随凭据轮换改变',
    `registered_by` BIGINT NOT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金只读目录来源身份';

CREATE TABLE IF NOT EXISTS `biz_daikin_project_mapping` (
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `site_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL COMMENT '厂家项目原始身份',
    `building_id` VARCHAR(32) NOT NULL,
    `mapping_version` INT NOT NULL,
    `mapped_by` BIGINT NOT NULL,
    `mapped_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`source_id`,`site_id`),
    KEY `idx_daikin_mapping_building` (`building_id`,`source_id`,`site_id`),
    CONSTRAINT `fk_daikin_mapping_source` FOREIGN KEY (`source_id`)
        REFERENCES `biz_daikin_source` (`source_id`),
    CONSTRAINT `fk_daikin_mapping_building` FOREIGN KEY (`building_id`)
        REFERENCES `building` (`building_id`),
    CONSTRAINT `chk_daikin_mapping_version` CHECK (`mapping_version` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金项目当前建筑映射';

CREATE TABLE IF NOT EXISTS `biz_daikin_project_mapping_version` (
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `site_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `mapping_version` INT NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `mapped_by` BIGINT NOT NULL,
    `mapped_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`source_id`,`site_id`,`mapping_version`),
    CONSTRAINT `fk_daikin_mapping_version_source` FOREIGN KEY (`source_id`)
        REFERENCES `biz_daikin_source` (`source_id`),
    CONSTRAINT `fk_daikin_mapping_version_building` FOREIGN KEY (`building_id`)
        REFERENCES `building` (`building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金项目建筑映射版本历史';

CREATE TABLE IF NOT EXISTS `biz_daikin_directory` (
    `pending_id` VARCHAR(32) NOT NULL,
    `identity_hash` CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '完整厂家组合身份的长度前缀SHA-256',
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `site_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `controller_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `device_kind` VARCHAR(10) NOT NULL COMMENT 'INDOOR或OUTDOOR',
    `unit_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `equipment_id` VARCHAR(200) COLLATE utf8mb4_bin DEFAULT NULL,
    `site_name` VARCHAR(500) DEFAULT NULL,
    `device_name` VARCHAR(500) DEFAULT NULL,
    `observed_at` DATETIME(3) NOT NULL COMMENT '平台成功解析该设备的时间',
    `last_catalog_at` DATETIME(3) NOT NULL COMMENT '最近一次包含该设备的完整目录轮次',
    `missing` TINYINT(1) NOT NULL DEFAULT 0,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`pending_id`),
    UNIQUE KEY `uk_daikin_directory_identity_hash` (`source_id`,`identity_hash`),
    KEY `idx_daikin_directory_project` (`source_id`,`site_id`,`missing`,`pending_id`),
    CONSTRAINT `fk_daikin_directory_pending` FOREIGN KEY (`pending_id`)
        REFERENCES `biz_pending_device` (`pending_id`),
    CONSTRAINT `fk_daikin_directory_source` FOREIGN KEY (`source_id`)
        REFERENCES `biz_daikin_source` (`source_id`),
    CONSTRAINT `chk_daikin_directory_kind` CHECK (`device_kind` IN ('INDOOR','OUTDOOR')),
    CONSTRAINT `chk_daikin_directory_missing` CHECK (`missing` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金厂家完整目录待接入扩展';

CREATE TABLE IF NOT EXISTS `biz_daikin_catalog_sync` (
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `device_kind` VARCHAR(10) NOT NULL,
    `completed_at` DATETIME(3) NOT NULL,
    `catalog_hash` CHAR(64) NOT NULL COMMENT '同时间完整目录幂等与冲突校验',
    PRIMARY KEY (`source_id`,`device_kind`),
    CONSTRAINT `fk_daikin_sync_source` FOREIGN KEY (`source_id`)
        REFERENCES `biz_daikin_source` (`source_id`),
    CONSTRAINT `chk_daikin_sync_kind` CHECK (`device_kind` IN ('INDOOR','OUTDOOR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金完整目录成功轮次';
