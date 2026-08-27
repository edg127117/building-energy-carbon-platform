-- 统一审计只补齐各权威审计源的公共索引字段，不复制领域完整事实。
ALTER TABLE `biz_collection_config_audit_log`
    ADD COLUMN `review_request_id` VARCHAR(32) NULL AFTER `version_id`,
    ADD COLUMN `reason_code` VARCHAR(64) NULL AFTER `result`,
    ADD COLUMN `trace_id` VARCHAR(64) NULL AFTER `reason_code`,
    ADD COLUMN `environment_mode` VARCHAR(20) NULL AFTER `trace_id`,
    ADD COLUMN `self_approval_dev_mode` TINYINT(1) NOT NULL DEFAULT 0 AFTER `environment_mode`,
    ADD KEY `idx_collection_audit_public_building` (`building_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_collection_audit_public_operator` (`operator_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_collection_audit_public_result` (`result`,`operation_time`,`audit_id`),
    ADD KEY `idx_collection_audit_public_trace` (`trace_id`),
    ADD KEY `idx_collection_audit_retention` (`operation_time`,`audit_id`);

ALTER TABLE `biz_quality_usage_audit_log`
    ADD COLUMN `review_request_id` VARCHAR(32) NULL AFTER `version_id`,
    ADD COLUMN `trace_id` VARCHAR(64) NULL AFTER `reason_code`,
    ADD COLUMN `environment_mode` VARCHAR(20) NULL AFTER `trace_id`,
    ADD COLUMN `self_approval_dev_mode` TINYINT(1) NOT NULL DEFAULT 0 AFTER `environment_mode`,
    ADD KEY `idx_quality_usage_audit_public_building` (`building_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_quality_usage_audit_public_operator` (`operator_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_quality_usage_audit_public_object` (`object_type`,`object_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_quality_usage_audit_public_result` (`result`,`operation_time`,`audit_id`),
    ADD KEY `idx_quality_usage_audit_public_trace` (`trace_id`),
    ADD KEY `idx_quality_usage_audit_retention` (`operation_time`,`audit_id`);

ALTER TABLE `biz_device_parameter_audit_log`
    ADD COLUMN `review_request_id` VARCHAR(32) NULL AFTER `version_id`,
    ADD COLUMN `trace_id` VARCHAR(64) NULL AFTER `reason_code`,
    ADD COLUMN `environment_mode` VARCHAR(20) NULL AFTER `trace_id`,
    ADD COLUMN `self_approval_dev_mode` TINYINT(1) NOT NULL DEFAULT 0 AFTER `environment_mode`,
    ADD KEY `idx_device_parameter_audit_public_building` (`building_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_device_parameter_audit_public_operator` (`operator_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_device_parameter_audit_public_result` (`result`,`operation_time`,`audit_id`),
    ADD KEY `idx_device_parameter_audit_public_trace` (`trace_id`),
    ADD KEY `idx_device_parameter_audit_retention` (`operation_time`,`audit_id`);

ALTER TABLE `biz_relation_audit_log`
    ADD COLUMN `actor_type` VARCHAR(20) NULL AFTER `building_id`,
    ADD COLUMN `trace_id` VARCHAR(64) NULL AFTER `result`,
    ADD COLUMN `environment_mode` VARCHAR(20) NULL AFTER `trace_id`,
    ADD COLUMN `self_approval_dev_mode` TINYINT(1) NOT NULL DEFAULT 0 AFTER `environment_mode`,
    ADD KEY `idx_relation_audit_public_building` (`building_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_relation_audit_public_operator` (`operator_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_relation_audit_public_object` (`object_type`,`object_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_relation_audit_public_result` (`result`,`operation_time`,`audit_id`),
    ADD KEY `idx_relation_audit_public_trace` (`trace_id`),
    ADD KEY `idx_relation_audit_retention` (`operation_time`,`audit_id`);

ALTER TABLE `biz_onboarding_audit_log`
    ADD COLUMN `building_id` VARCHAR(32) NULL AFTER `audit_id`,
    ADD COLUMN `actor_type` VARCHAR(20) NULL AFTER `building_id`,
    ADD COLUMN `version_id` VARCHAR(32) NULL AFTER `object_id`,
    ADD COLUMN `review_request_id` VARCHAR(32) NULL AFTER `version_id`,
    ADD COLUMN `reason_code` VARCHAR(64) NULL AFTER `result`,
    ADD COLUMN `trace_id` VARCHAR(64) NULL AFTER `reason_code`,
    ADD COLUMN `environment_mode` VARCHAR(20) NULL AFTER `trace_id`,
    ADD COLUMN `self_approval_dev_mode` TINYINT(1) NOT NULL DEFAULT 0 AFTER `environment_mode`,
    ADD KEY `idx_onboarding_audit_public_building` (`building_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_onboarding_audit_public_operator` (`operator_id`,`operation_time`,`audit_id`),
    ADD KEY `idx_onboarding_audit_public_result` (`result`,`operation_time`,`audit_id`),
    ADD KEY `idx_onboarding_audit_public_trace` (`trace_id`),
    ADD KEY `idx_onboarding_audit_retention` (`operation_time`,`audit_id`);

-- 历史行缺失原始 traceId 或部署环境时保持 NULL，禁止迁移时补造并不存在的证据。
CREATE TABLE `sys_audit_export_job` (
    `export_id` VARCHAR(32) NOT NULL,
    `requested_by` BIGINT NOT NULL,
    `purpose` VARCHAR(500) NOT NULL,
    `query_json` JSON NOT NULL,
    `query_sha256` CHAR(64) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `row_count` INT NULL,
    `file_path` VARCHAR(1000) NULL,
    `file_sha256` CHAR(64) NULL,
    `expires_at` DATETIME(3) NOT NULL,
    `error_code` VARCHAR(64) NULL,
    `trace_id` VARCHAR(64) NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `started_at` DATETIME(3) NULL,
    `completed_at` DATETIME(3) NULL,
    `downloaded_by` BIGINT NULL,
    `downloaded_at` DATETIME(3) NULL,
    PRIMARY KEY (`export_id`),
    KEY `idx_audit_export_owner_time` (`requested_by`,`created_at`,`export_id`),
    KEY `idx_audit_export_cleanup` (`status`,`expires_at`,`export_id`),
    KEY `idx_audit_export_trace` (`trace_id`),
    CONSTRAINT `chk_audit_export_status` CHECK (`status` IN
        ('PENDING','RUNNING','COMPLETED','FAILED','EXPIRED')),
    CONSTRAINT `chk_audit_export_row_count` CHECK (`row_count` IS NULL OR `row_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='默认脱敏审计导出任务与文件生命周期证据';
