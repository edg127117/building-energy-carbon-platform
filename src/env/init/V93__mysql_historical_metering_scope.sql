-- 历史计量依据单独审核，保留关系实际激活时间与当前投影。
CREATE TABLE biz_historical_metering_scope (
    scope_id VARCHAR(32) NOT NULL PRIMARY KEY,
    building_id VARCHAR(32) NOT NULL,
    relation_version_id VARCHAR(32) NOT NULL,
    start_epoch_ms BIGINT NOT NULL,
    end_epoch_ms BIGINT NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    evidence_reference VARCHAR(500) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(24) NOT NULL,
    submitted_by BIGINT NOT NULL,
    reviewed_by BIGINT NULL,
    review_reason VARCHAR(500) NULL,
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at DATETIME NULL,
    CONSTRAINT chk_historical_metering_period CHECK (start_epoch_ms < end_epoch_ms),
    CONSTRAINT chk_historical_metering_status CHECK (status IN ('PENDING_REVIEW','APPROVED','REJECTED')),
    INDEX idx_historical_metering_building (building_id,status,start_epoch_ms,end_epoch_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
