-- 无实体表来源和整月填报独立治理；不填现场事实、不授予职责、不修改旧快照。
CREATE TABLE IF NOT EXISTS biz_energy_reporting_scope (
 building_id VARCHAR(32) NOT NULL PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS biz_energy_reporting_record (
 record_id VARCHAR(32) NOT NULL PRIMARY KEY, building_id VARCHAR(32) NOT NULL,
 record_kind VARCHAR(16) NOT NULL, status VARCHAR(24) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 month_date VARCHAR(10), payload_json LONGTEXT NOT NULL,
 INDEX idx_reporting_scope(building_id,record_kind,status,month_date),
 CONSTRAINT chk_reporting_kind CHECK (record_kind IN ('SOURCE','BATCH'))
);
CREATE TABLE IF NOT EXISTS biz_energy_reporting_receipt (
 building_id VARCHAR(32) NOT NULL, request_key VARCHAR(100) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, response_json LONGTEXT NOT NULL,
 PRIMARY KEY(building_id,request_key)
);

-- 全局参数包仅串行导入并记录幂等回执，不触及任何建筑配置或审核状态。
CREATE TABLE IF NOT EXISTS biz_energy_parameter_package_import_lock (
 scope_key VARCHAR(64) NOT NULL PRIMARY KEY, updated_at DATETIME NOT NULL
);
INSERT INTO biz_energy_parameter_package_import_lock(scope_key,updated_at) VALUES ('GLOBAL_PARAMETER_PACKAGES',CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE scope_key=scope_key;
CREATE TABLE IF NOT EXISTS biz_energy_parameter_package_import_receipt (
 receipt_id CHAR(32) NOT NULL PRIMARY KEY, scope_key VARCHAR(64) NOT NULL,
 idempotency_key VARCHAR(100) NOT NULL, request_hash CHAR(64) NOT NULL, actor_id BIGINT NOT NULL,
 package_code VARCHAR(16) NOT NULL, status VARCHAR(32) NOT NULL, response_json LONGTEXT,
 created_at DATETIME NOT NULL, completed_at DATETIME,
 UNIQUE KEY uk_parameter_package_receipt(scope_key,idempotency_key)
);
