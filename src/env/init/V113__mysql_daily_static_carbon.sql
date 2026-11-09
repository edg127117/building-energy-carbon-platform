-- DAY monitoring has its own scope, immutable evidence and idempotency; it never writes HOUR facts.
CREATE TABLE biz_carbon_daily_static_scope (
 building_id VARCHAR(32) NOT NULL,
 day_date DATE NOT NULL,
 revision INT NOT NULL DEFAULT 0,
 PRIMARY KEY (building_id,day_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE biz_carbon_daily_static_batch (
 batch_id VARCHAR(32) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 day_date DATE NOT NULL,
 revision INT NOT NULL,
 idempotency_key VARCHAR(100) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 calculation_as_of BIGINT NOT NULL,
 source_version CHAR(64) NOT NULL,
 view_json LONGTEXT NOT NULL,
 view_hash CHAR(64) NOT NULL,
 evidence_json LONGTEXT NOT NULL,
 evidence_hash CHAR(64) NOT NULL,
 created_by BIGINT NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE KEY uk_daily_static_key (building_id,idempotency_key),
 UNIQUE KEY uk_daily_static_revision (building_id,day_date,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
