-- 可重建的日趋势展示结果及恢复任务；不改写读数、封账和核算证据。
CREATE TABLE biz_daily_trend_read_model (
 building_id VARCHAR(32) NOT NULL,
 month_key CHAR(7) NOT NULL,
 requested_by BIGINT NOT NULL,
 source_version CHAR(64) NOT NULL,
 built_version CHAR(64) NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 summary_json LONGTEXT NULL,
 summary_hash CHAR(64) NULL,
 lease_token VARCHAR(32) NULL,
 lease_until BIGINT NOT NULL DEFAULT 0,
 next_check BIGINT NOT NULL DEFAULT 0,
 attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(64) NULL,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY (building_id,month_key),
 KEY idx_daily_trend_work (state,next_check,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
