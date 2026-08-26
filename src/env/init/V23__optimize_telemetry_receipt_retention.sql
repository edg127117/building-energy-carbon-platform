USE `iot_platform`;

-- 成功投递证据改由监控系统聚合，MySQL 只保留消息持久化终态和异常明细。
ALTER TABLE `biz_telemetry_receipt`
    DROP INDEX `idx_receipt_status_persisted`,
    DROP COLUMN `device_puback_state`,
    DROP COLUMN `adapter_publish_puback_state`,
    DROP COLUMN `platform_consumer_ack_state`,
    DROP COLUMN `application_ack_puback_state`,
    DROP COLUMN `application_ack_published_at`,
    ADD INDEX `idx_receipt_cleanup` (`persisted_at`, `canonical_message_id`);

-- 异常明细保留 180 天，独立时间索引保证持续批量清理不扫描整表。
ALTER TABLE `biz_telemetry_receipt_failure`
    ADD INDEX `idx_receipt_failure_occurred` (`occurred_at`);
