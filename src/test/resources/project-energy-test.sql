-- 隔离 API 测试所需的分类引用与项目版本；不运行真实数据库迁移。
CREATE TABLE IF NOT EXISTS biz_energy_classification_version (
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

CREATE TABLE IF NOT EXISTS biz_carbon_project_scope (
 building_id VARCHAR(32) NOT NULL PRIMARY KEY,
 revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS biz_carbon_project_version (
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
