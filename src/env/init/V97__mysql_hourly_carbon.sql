-- 独立小时结果与既有人工批次审核时间分开保存；历史审批不回填该字段。
ALTER TABLE biz_energy_manual_batch ADD COLUMN reviewed_at BIGINT NULL;

CREATE TABLE biz_carbon_hourly_scope (
    building_id VARCHAR(32) NOT NULL,
    day_date DATE NOT NULL,
    revision INT NOT NULL DEFAULT 0,
    PRIMARY KEY (building_id, day_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE biz_carbon_hourly_batch (
    batch_id VARCHAR(32) NOT NULL,
    building_id VARCHAR(32) NOT NULL,
    day_date DATE NOT NULL,
    revision INT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    calculation_as_of BIGINT NOT NULL,
    view_json LONGTEXT NOT NULL,
    view_hash CHAR(64) NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (batch_id),
    UNIQUE KEY uk_carbon_hourly_batch_idempotency (building_id, idempotency_key),
    UNIQUE KEY uk_carbon_hourly_batch_revision (building_id, day_date, revision),
    KEY idx_carbon_hourly_batch_date_asof_revision (building_id, day_date, calculation_as_of, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
