-- 账号开通与密码重置只保存一次性令牌哈希，原始令牌只在批准命令的执行响应中返回一次。

ALTER TABLE `sys_user`
    ADD COLUMN `activation_pending` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '1表示账号尚未通过一次性令牌设置初始密码' AFTER `del_flag`;

CREATE TABLE `sys_password_setup_token` (
    `token_id` VARCHAR(32) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `token_hash` CHAR(64) NOT NULL COMMENT '原始令牌的SHA-256，不保存原始令牌',
    `purpose` VARCHAR(20) NOT NULL COMMENT 'ACTIVATION或RESET',
    `status` VARCHAR(20) NOT NULL COMMENT 'ACTIVE、USED或REVOKED',
    `expires_at` DATETIME(3) NOT NULL,
    `source_request_id` VARCHAR(32) NOT NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `used_at` DATETIME(3) NULL,
    PRIMARY KEY (`token_id`),
    UNIQUE KEY `uk_password_setup_token_hash` (`token_hash`),
    UNIQUE KEY `uk_password_setup_source` (`source_request_id`,`purpose`),
    KEY `idx_password_setup_user_status` (`user_id`,`status`,`expires_at`),
    CONSTRAINT `chk_password_setup_purpose` CHECK (`purpose` IN ('ACTIVATION','RESET')),
    CONSTRAINT `chk_password_setup_status` CHECK (`status` IN ('ACTIVE','USED','REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号激活与密码重置一次性令牌';
