-- 可配置设备接入工作包 A：产品模板与未知设备发现的本地 MySQL 业务表。
-- 本迁移只新增业务档案，不改动已绑定设备、别名或 TDengine 正式时序链。
-- 可在已有 iot_platform 库中手工执行；重复执行仅复用已存在的表结构。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `iot_platform`;

CREATE TABLE IF NOT EXISTS `biz_device_product` (
    `product_id` VARCHAR(32) NOT NULL COMMENT '平台内部产品型号ID',
    `product_code` VARCHAR(50) NOT NULL COMMENT '平台唯一产品编码',
    `product_name` VARCHAR(100) NOT NULL COMMENT '产品展示名称',
    `manufacturer` VARCHAR(100) DEFAULT NULL COMMENT '设备厂商',
    `model` VARCHAR(100) DEFAULT NULL COMMENT '设备型号',
    `equipment_type_code` VARCHAR(20) NOT NULL COMMENT '对应平台设备类型',
    `expected_profile_code` VARCHAR(50) NOT NULL COMMENT '允许的云端兼容协议族',
    `identity_type` VARCHAR(20) NOT NULL COMMENT '预期外部身份类型',
    `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT、ENABLED、DISABLED',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`product_id`),
    UNIQUE KEY `uk_device_product_code` (`product_code`),
    KEY `idx_device_product_type_status` (`equipment_type_code`, `status`),
    CONSTRAINT `chk_device_product_status`
        CHECK (`status` IN ('DRAFT', 'ENABLED', 'DISABLED')),
    CONSTRAINT `fk_device_product_equipment_type`
        FOREIGN KEY (`equipment_type_code`) REFERENCES `biz_equipment_type` (`type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可复用设备产品接入模板';

CREATE TABLE IF NOT EXISTS `biz_product_point_template` (
    `template_point_id` VARCHAR(32) NOT NULL COMMENT '产品测点模板内部ID',
    `product_id` VARCHAR(32) NOT NULL COMMENT '所属产品型号',
    `metric_code` VARCHAR(100) NOT NULL COMMENT '标准多指标报文指标代码',
    `point_name_template` VARCHAR(100) NOT NULL COMMENT '实例化后的默认测点名称',
    `suffix_code` VARCHAR(20) DEFAULT NULL COMMENT '平台测点后缀语义',
    `unit` VARCHAR(20) NOT NULL COMMENT '平台标准单位',
    `min_value` DECIMAL(12,4) DEFAULT NULL COMMENT '可选运行下限',
    `max_value` DECIMAL(12,4) DEFAULT NULL COMMENT '可选运行上限',
    `for_calc` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否允许进入既有公式输入',
    `required_flag` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '激活前是否必须映射',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示排序',
    `status` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1-启用，0-停用',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`template_point_id`),
    UNIQUE KEY `uk_product_point_template_metric` (`product_id`, `metric_code`),
    KEY `idx_product_point_template_status` (`product_id`, `status`, `sort_order`),
    CONSTRAINT `fk_product_point_template_product`
        FOREIGN KEY (`product_id`) REFERENCES `biz_device_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品型号的确定性测点默认模板';

CREATE TABLE IF NOT EXISTS `biz_pending_device` (
    `pending_id` VARCHAR(32) NOT NULL COMMENT '待绑定设备记录ID',
    `identity_type` VARCHAR(20) NOT NULL COMMENT '外部身份类型',
    `identity_value` VARCHAR(100) NOT NULL COMMENT '外部身份值',
    `profile_code` VARCHAR(50) NOT NULL COMMENT '最近上报的协议代码',
    `last_profile_version` INT NOT NULL COMMENT '最近上报的协议版本',
    `first_seen_time` DATETIME(3) NOT NULL COMMENT '首次发现的服务器接收时间',
    `last_seen_time` DATETIME(3) NOT NULL COMMENT '最近发现的服务器接收时间',
    `report_count` BIGINT NOT NULL DEFAULT 1 COMMENT '未知身份上报次数',
    `latest_event_time` DATETIME(3) NOT NULL COMMENT '最近样例的事件时间',
    `latest_time_source` VARCHAR(20) NOT NULL COMMENT 'DEVICE_REPORTED 或 SERVER_RECEIVED',
    `latest_metrics_json` TEXT NOT NULL COMMENT '已完成大小限制的规范化指标样例，不保存原始载荷',
    `sample_truncated` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '规范化样例是否因上限截断',
    `status` VARCHAR(20) NOT NULL DEFAULT 'DISCOVERED' COMMENT 'DISCOVERED、BOUND、IGNORED',
    `bound_identity_id` VARCHAR(32) DEFAULT NULL COMMENT '绑定完成后的正式设备身份ID',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`pending_id`),
    UNIQUE KEY `uk_pending_device_identity` (`identity_type`, `identity_value`),
    KEY `idx_pending_device_expiry` (`status`, `last_seen_time`, `pending_id`),
    KEY `idx_pending_device_bound_identity` (`bound_identity_id`),
    CONSTRAINT `chk_pending_device_status`
        CHECK (`status` IN ('DISCOVERED', 'BOUND', 'IGNORED')),
    CONSTRAINT `chk_pending_device_time_source`
        CHECK (`latest_time_source` IN ('DEVICE_REPORTED', 'SERVER_RECEIVED')),
    CONSTRAINT `fk_pending_device_bound_identity`
        FOREIGN KEY (`bound_identity_id`) REFERENCES `biz_device_identity` (`identity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='未知标准报文的有界待绑定发现记录';
