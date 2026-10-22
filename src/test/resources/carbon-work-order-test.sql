CREATE TABLE IF NOT EXISTS biz_carbon_configuration_command (
 command_id VARCHAR(32) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 project_version_id VARCHAR(32) NOT NULL,
 idempotency_key VARCHAR(80) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 guide_fingerprint CHAR(64) NOT NULL,
 receipt_json TEXT NOT NULL,
 created_by BIGINT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_carbon_configuration_command (building_id,project_version_id,idempotency_key)
);
