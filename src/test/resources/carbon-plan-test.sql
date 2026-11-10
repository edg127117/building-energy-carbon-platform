-- H2隔离契约结构；真实MySQL迁移另由专项测试验证。
CREATE TABLE IF NOT EXISTS biz_carbon_plan_scope (
 building_id VARCHAR(32) NOT NULL, plan_year INT NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(building_id,plan_year)
);
CREATE TABLE IF NOT EXISTS biz_carbon_plan_version (
 version_id VARCHAR(32) NOT NULL PRIMARY KEY, building_id VARCHAR(32) NOT NULL,
 plan_year INT NOT NULL, version_no INT NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 status VARCHAR(24) NOT NULL, configuration_json LONGTEXT NULL, copied_from_version_id VARCHAR(32) NULL,
 reason VARCHAR(500) NOT NULL, created_by BIGINT NOT NULL, reviewed_by BIGINT NULL,
 activated_by BIGINT NULL, activated_at BIGINT NULL, created_at BIGINT NOT NULL,
 UNIQUE(building_id,plan_year,version_no)
);
