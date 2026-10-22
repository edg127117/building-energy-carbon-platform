-- 项目能源及碳边界独立版本；存量建筑没有启用版本时保持既有核算路径。
CREATE TABLE biz_carbon_project_scope (
 building_id VARCHAR(32) NOT NULL PRIMARY KEY,
 revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE biz_carbon_project_version (
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
 activated_by BIGINT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE KEY uk_carbon_project_version (building_id, version_no),
 KEY idx_carbon_project_effective (building_id, status, effective_from)
);
