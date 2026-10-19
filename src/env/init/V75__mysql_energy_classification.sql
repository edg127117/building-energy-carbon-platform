-- 配置整体版本冻结；不改写既有采集身份、关系版本、封账快照或碳核算配置。
CREATE TABLE biz_energy_classification_scope (
 building_id VARCHAR(32) NOT NULL PRIMARY KEY,
 revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE biz_energy_classification_version (
 version_id VARCHAR(32) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 version_no INT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 status VARCHAR(24) NOT NULL,
 effective_from BIGINT NULL,
 effective_to BIGINT NULL,
 configuration_json LONGTEXT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_by BIGINT NOT NULL,
 reviewed_by BIGINT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE KEY uk_classification_version (building_id, version_no),
 KEY idx_classification_effective (building_id, status, effective_from)
);
CREATE TABLE biz_energy_manual_batch (
 batch_id VARCHAR(32) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 idempotency_key VARCHAR(80) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 created_by BIGINT NOT NULL,
 reviewed_by BIGINT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE KEY uk_energy_manual_batch (building_id, idempotency_key)
);
CREATE TABLE biz_energy_manual_reading (
 fact_id VARCHAR(32) NOT NULL PRIMARY KEY,
 batch_id VARCHAR(32) NOT NULL,
 building_id VARCHAR(32) NOT NULL,
 point_id VARCHAR(80) NOT NULL,
 start_inclusive BIGINT NOT NULL,
 end_exclusive BIGINT NOT NULL,
 reading_json LONGTEXT NOT NULL,
 normalized_quantity DECIMAL(38,12) NULL,
 supersedes_fact_id VARCHAR(32) NULL,
 created_by BIGINT NOT NULL,
 KEY idx_energy_manual_period (building_id, start_inclusive, end_exclusive)
);
