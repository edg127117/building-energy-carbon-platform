USE `iot_platform`;

-- 受控接管的旧库可能已经人工执行过本迁移。逐列补齐可以让 Flyway 从 V3
-- baseline 继续推进，同时避免重复 ALTER 因“列已存在”中断整个升级链。
DROP PROCEDURE IF EXISTS `migrate_telemetry_reliability_v2_identity`;
DELIMITER //
CREATE PROCEDURE `migrate_telemetry_reliability_v2_identity`()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_device_identity'
          AND `COLUMN_NAME`='max_ack_mode'
    ) THEN
        ALTER TABLE `biz_device_identity`
            ADD COLUMN `max_ack_mode` VARCHAR(30) NOT NULL DEFAULT 'EVIDENCE_ONLY'
                COMMENT 'DEVICE_DIRECT/ADAPTER_PROXY/EVIDENCE_ONLY'
                AFTER `expected_profile_code`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_device_identity'
          AND `COLUMN_NAME`='correlation_policy'
    ) THEN
        ALTER TABLE `biz_device_identity`
            ADD COLUMN `correlation_policy` VARCHAR(40) NOT NULL DEFAULT 'NONE'
                COMMENT '受信任消息关联策略' AFTER `max_ack_mode`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_device_identity'
          AND `COLUMN_NAME`='device_ack_topic'
    ) THEN
        ALTER TABLE `biz_device_identity`
            ADD COLUMN `device_ack_topic` VARCHAR(200) DEFAULT NULL
                COMMENT '平台配置的设备响应Topic' AFTER `correlation_policy`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.`COLUMNS`
        WHERE `TABLE_SCHEMA`=DATABASE() AND `TABLE_NAME`='biz_device_identity'
          AND `COLUMN_NAME`='adapter_ack_topic'
    ) THEN
        ALTER TABLE `biz_device_identity`
            ADD COLUMN `adapter_ack_topic` VARCHAR(200) DEFAULT NULL
                COMMENT '平台配置的适配器响应Topic' AFTER `device_ack_topic`;
    END IF;
END//
DELIMITER ;

CALL `migrate_telemetry_reliability_v2_identity`();
DROP PROCEDURE `migrate_telemetry_reliability_v2_identity`;

CREATE TABLE IF NOT EXISTS `biz_telemetry_receipt` (
    `canonical_message_id` VARCHAR(64) NOT NULL,
    `identity_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `equip_id` VARCHAR(32) NOT NULL,
    `profile_code` VARCHAR(50) NOT NULL,
    `source_message_id` VARCHAR(128) DEFAULT NULL,
    `source_seq` BIGINT DEFAULT NULL,
    `collected_at` DATETIME(3) DEFAULT NULL,
    `adapter_received_at` DATETIME(3) NOT NULL,
    `first_platform_received_at` DATETIME(3) NOT NULL,
    `last_platform_received_at` DATETIME(3) NOT NULL,
    `persisted_at` DATETIME(3) NOT NULL,
    `retransmitted_at` DATETIME(3) DEFAULT NULL,
    `batch_id` VARCHAR(128) DEFAULT NULL,
    `id_source` VARCHAR(30) NOT NULL,
    `time_source` VARCHAR(30) NOT NULL,
    `dedup_mode` VARCHAR(20) NOT NULL,
    `payload_hash` CHAR(64) NOT NULL,
    `configured_ack_mode` VARCHAR(30) NOT NULL,
    `actual_ack_mode` VARCHAR(30) NOT NULL,
    `downgrade_reason` VARCHAR(100) DEFAULT NULL,
    `receipt_status` VARCHAR(40) NOT NULL,
    `result_code` VARCHAR(60) NOT NULL,
    `metric_count` INT NOT NULL,
    `attempt_count` INT NOT NULL DEFAULT 1,
    `device_puback_state` VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    `adapter_publish_puback_state` VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    `platform_consumer_ack_state` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `application_ack_puback_state` VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE',
    `application_ack_published_at` DATETIME(3) DEFAULT NULL,
    PRIMARY KEY (`canonical_message_id`),
    KEY `idx_receipt_building_persisted` (`building_id`, `persisted_at`),
    KEY `idx_receipt_equip_persisted` (`equip_id`, `persisted_at`),
    KEY `idx_receipt_status_persisted` (`receipt_status`, `persisted_at`),
    CONSTRAINT `fk_receipt_identity` FOREIGN KEY (`identity_id`)
        REFERENCES `biz_device_identity` (`identity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2遥测轻量终态回执';

CREATE TABLE IF NOT EXISTS `biz_telemetry_receipt_failure` (
    `failure_id` VARCHAR(32) NOT NULL,
    `canonical_message_id` VARCHAR(64) DEFAULT NULL,
    `building_id` VARCHAR(32) DEFAULT NULL,
    `failure_stage` VARCHAR(40) NOT NULL,
    `failure_code` VARCHAR(60) NOT NULL,
    `safe_detail` VARCHAR(500) DEFAULT NULL,
    `occurred_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`failure_id`),
    KEY `idx_receipt_failure_message_time` (`canonical_message_id`, `occurred_at`),
    KEY `idx_receipt_failure_building_time` (`building_id`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2遥测异常与重投明细';

CREATE TABLE IF NOT EXISTS `biz_mqtt_failure_aggregate` (
    `aggregate_id` VARCHAR(32) NOT NULL,
    `bucket_start` DATETIME(3) NOT NULL,
    `component` VARCHAR(30) NOT NULL,
    `failure_category` VARCHAR(60) NOT NULL,
    `broker_endpoint` VARCHAR(255) NOT NULL,
    `occurrence_count` BIGINT NOT NULL,
    `first_occurred_at` DATETIME(3) NOT NULL,
    `last_occurred_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`aggregate_id`),
    UNIQUE KEY `uk_mqtt_failure_bucket`
        (`bucket_start`, `component`, `failure_category`, `broker_endpoint`),
    KEY `idx_mqtt_failure_last` (`last_occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQTT和TLS分钟聚合故障';
