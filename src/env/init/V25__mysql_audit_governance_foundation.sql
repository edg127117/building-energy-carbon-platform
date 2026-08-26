-- 后台职责与系统敏感操作公共基础。领域审核表仍是各自权威事实，不在此建立中央审计副本。

CREATE TABLE IF NOT EXISTS `sys_backend_duty` (
    `duty_key` VARCHAR(64) NOT NULL COMMENT '代码内固定职责键',
    `duty_name` VARCHAR(100) NOT NULL COMMENT '职责名称',
    `description` VARCHAR(500) NOT NULL COMMENT '职责边界',
    `status` VARCHAR(20) NOT NULL COMMENT 'ENABLED或DISABLED',
    `risk_level` VARCHAR(20) NOT NULL COMMENT '风险等级',
    `version` INT NOT NULL DEFAULT 1 COMMENT '目录版本',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`duty_key`),
    CONSTRAINT `chk_backend_duty_status` CHECK (`status` IN ('ENABLED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立后台职责目录';

CREATE TABLE IF NOT EXISTS `sys_user_backend_duty` (
    `assignment_id` VARCHAR(32) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `duty_key` VARCHAR(64) NOT NULL,
    `status` VARCHAR(20) NOT NULL COMMENT 'ACTIVE或REVOKED',
    `effective_at` DATETIME(3) NOT NULL,
    `expires_at` DATETIME(3) NULL,
    `source_request_id` VARCHAR(32) NULL COMMENT '授予来源申请',
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `revoked_by` BIGINT NULL,
    `revoked_at` DATETIME(3) NULL,
    `revoke_request_id` VARCHAR(32) NULL,
    PRIMARY KEY (`assignment_id`),
    UNIQUE KEY `uk_user_backend_duty` (`user_id`,`duty_key`),
    KEY `idx_user_backend_duty_active` (`user_id`,`status`,`effective_at`,`expires_at`),
    CONSTRAINT `fk_user_backend_duty_catalog` FOREIGN KEY (`duty_key`)
        REFERENCES `sys_backend_duty` (`duty_key`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_user_backend_duty_status` CHECK (`status` IN ('ACTIVE','REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户独立后台职责授权';

CREATE TABLE IF NOT EXISTS `sys_sensitive_change_request` (
    `request_id` VARCHAR(32) NOT NULL,
    `operation_code` VARCHAR(64) NOT NULL COMMENT '代码注册的操作键',
    `status` VARCHAR(24) NOT NULL,
    `building_id` VARCHAR(32) NULL,
    `target_type` VARCHAR(64) NOT NULL,
    `target_id` VARCHAR(128) NOT NULL,
    `command_json` JSON NOT NULL COMMENT '处理器规范化白名单命令',
    `request_sha256` CHAR(64) NOT NULL,
    `impact_summary` VARCHAR(1000) NOT NULL COMMENT '脱敏影响摘要',
    `submitted_by` BIGINT NOT NULL,
    `submitted_at` DATETIME(3) NULL,
    `reviewer_id` BIGINT NULL,
    `review_comment` VARCHAR(500) NULL,
    `reviewed_at` DATETIME(3) NULL,
    `executed_at` DATETIME(3) NULL,
    `execution_error_code` VARCHAR(64) NULL,
    `idempotency_key` VARCHAR(100) NOT NULL,
    `trace_id` VARCHAR(64) NOT NULL,
    `environment_mode` VARCHAR(20) NOT NULL,
    `self_approval_dev_mode` TINYINT(1) NOT NULL DEFAULT 0,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`request_id`),
    UNIQUE KEY `uk_sensitive_change_idempotency` (`submitted_by`,`idempotency_key`),
    KEY `idx_sensitive_change_status_time` (`status`,`create_time`,`request_id`),
    KEY `idx_sensitive_change_target` (`target_type`,`target_id`,`create_time`,`request_id`),
    KEY `idx_sensitive_change_building` (`building_id`,`create_time`,`request_id`),
    KEY `idx_sensitive_change_trace` (`trace_id`),
    CONSTRAINT `chk_sensitive_change_status` CHECK (`status` IN
        ('DRAFT','PENDING_REVIEW','APPROVED','EXECUTED','REJECTED','WITHDRAWN','EXECUTION_FAILED')),
    CONSTRAINT `chk_sensitive_change_environment` CHECK (`environment_mode` IN
        ('DEVELOPMENT','TEST','PRODUCTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用系统敏感变更申请';

CREATE TABLE IF NOT EXISTS `sys_security_audit_event` (
    `audit_id` VARCHAR(32) NOT NULL,
    `source_module` VARCHAR(50) NOT NULL,
    `building_id` VARCHAR(32) NULL,
    `actor_type` VARCHAR(20) NOT NULL,
    `operator_id` BIGINT NULL,
    `action_type` VARCHAR(64) NOT NULL,
    `object_type` VARCHAR(64) NOT NULL,
    `object_id` VARCHAR(128) NOT NULL,
    `version_id` VARCHAR(64) NULL,
    `review_request_id` VARCHAR(32) NULL,
    `before_summary` VARCHAR(1000) NULL,
    `after_summary` VARCHAR(1000) NULL,
    `result` VARCHAR(20) NOT NULL,
    `reason_code` VARCHAR(64) NULL,
    `trace_id` VARCHAR(64) NOT NULL,
    `operation_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `environment_mode` VARCHAR(20) NOT NULL,
    `self_approval_dev_mode` TINYINT(1) NOT NULL DEFAULT 0,
    `first_at` DATETIME(3) NULL,
    `last_at` DATETIME(3) NULL,
    `attempt_count` INT NOT NULL DEFAULT 1,
    PRIMARY KEY (`audit_id`),
    KEY `idx_security_audit_building_time` (`building_id`,`operation_time`,`audit_id`),
    KEY `idx_security_audit_operator_time` (`operator_id`,`operation_time`,`audit_id`),
    KEY `idx_security_audit_object_time` (`object_type`,`object_id`,`operation_time`,`audit_id`),
    KEY `idx_security_audit_result_time` (`result`,`operation_time`,`audit_id`),
    KEY `idx_security_audit_trace` (`trace_id`),
    KEY `idx_security_audit_retention` (`operation_time`,`audit_id`),
    CONSTRAINT `chk_security_audit_result` CHECK (`result` IN ('SUCCESS','REJECTED','DENIED','FAILED')),
    CONSTRAINT `chk_security_audit_actor` CHECK (`actor_type` IN ('USER','SYSTEM','DEVICE','MIGRATION')),
    CONSTRAINT `chk_security_audit_environment` CHECK (`environment_mode` IN
        ('DEVELOPMENT','TEST','PRODUCTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可替换实现的系统安全审计事件';

INSERT INTO `sys_backend_duty`
    (`duty_key`,`duty_name`,`description`,`status`,`risk_level`,`version`)
VALUES
    ('BACKOFFICE_CHANGE_SUBMITTER','后台敏感变更提交','提交代码已登记的系统级敏感变更申请','ENABLED','HIGH',1),
    ('BACKOFFICE_CHANGE_REVIEWER','后台敏感变更审核','批准或拒绝系统级敏感变更申请','ENABLED','CRITICAL',1),
    ('AUDIT_EVIDENCE_VIEWER','审计证据查看','查询授权建筑范围内的审计证据','ENABLED','HIGH',1),
    ('AUDIT_EVIDENCE_EXPORTER','审计证据导出','创建和下载默认脱敏审计导出','ENABLED','CRITICAL',1),
    ('AUDIT_RETENTION_MANAGER','审计保留管理','维护审计保留策略草稿','ENABLED','CRITICAL',1),
    ('AUDIT_EVIDENCE_HOLD_MANAGER','审计证据保全','设置保全并提交解除或例外删除申请','ENABLED','CRITICAL',1)
ON DUPLICATE KEY UPDATE
    `duty_name` = VALUES(`duty_name`),
    `description` = VALUES(`description`),
    `risk_level` = VALUES(`risk_level`),
    `version` = VALUES(`version`);
