-- 可删除后重新生成的联合流/强度展示结果；不修改核算、配置或采集事实。
CREATE TABLE biz_carbon_display_read_model (
 query_key CHAR(64) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 query_kind VARCHAR(24) NOT NULL,
 query_json VARCHAR(2000) NOT NULL,
 requested_by BIGINT NOT NULL,
 source_version CHAR(64) NOT NULL,
 built_version CHAR(64) NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 result_json LONGTEXT NULL,
 result_hash CHAR(64) NULL,
 lease_token VARCHAR(32) NULL,
 lease_until BIGINT NOT NULL DEFAULT 0,
 next_check BIGINT NOT NULL DEFAULT 0,
 last_error VARCHAR(64) NULL,
 error_status INT NULL,
 updated_at BIGINT NOT NULL,
 KEY idx_carbon_display_work (next_check,state,lease_until),
 KEY idx_carbon_display_building (building_id,query_kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
