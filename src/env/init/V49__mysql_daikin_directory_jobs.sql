-- 大金目录同步任务仅保存调度状态和固定错误码，不保存厂家凭据、URL、报文或设备清单。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

CREATE TABLE IF NOT EXISTS `biz_daikin_directory_sync_job` (
    `job_id` CHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `source_id` VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `attempts` INT NOT NULL DEFAULT 0,
    `fence_token` BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增，阻止旧worker提交',
    `lease_token` CHAR(36) COLLATE utf8mb4_bin DEFAULT NULL,
    `lease_until` DATETIME(3) DEFAULT NULL,
    `next_attempt_at` DATETIME(3) NOT NULL,
    `error_code` VARCHAR(64) DEFAULT NULL COMMENT '仅允许安全固定代码',
    `requested_by` BIGINT DEFAULT NULL COMMENT 'NULL表示后台定时任务',
    `create_time` DATETIME(3) NOT NULL,
    `update_time` DATETIME(3) NOT NULL,
    `completed_at` DATETIME(3) DEFAULT NULL,
    PRIMARY KEY (`job_id`),
    KEY `idx_daikin_sync_job_due` (`status`,`next_attempt_at`,`create_time`),
    KEY `idx_daikin_sync_job_source` (`source_id`,`create_time`),
    CONSTRAINT `fk_daikin_sync_job_source` FOREIGN KEY (`source_id`)
        REFERENCES `biz_daikin_source` (`source_id`),
    CONSTRAINT `chk_daikin_sync_job_status` CHECK
        (`status` IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED')),
    CONSTRAINT `chk_daikin_sync_job_attempts` CHECK (`attempts` >= 0),
    CONSTRAINT `chk_daikin_sync_job_fence` CHECK (`fence_token` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金完整目录后台同步任务';
