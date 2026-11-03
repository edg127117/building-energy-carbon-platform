-- 年度计划仅冻结基准与目标，不触发核算；按建筑年度串行换版，旧批准内容永久保留。
CREATE TABLE biz_carbon_plan_scope (
 building_id VARCHAR(32) NOT NULL,
 plan_year INT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY (building_id, plan_year)
);
CREATE TABLE biz_carbon_plan_version (
 version_id VARCHAR(32) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 plan_year INT NOT NULL,
 version_no INT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 status VARCHAR(24) NOT NULL,
 configuration_json LONGTEXT NULL,
 copied_from_version_id VARCHAR(32) NULL,
 reason VARCHAR(500) NOT NULL,
 created_by BIGINT NOT NULL,
 reviewed_by BIGINT NULL,
 activated_by BIGINT NULL,
 activated_at BIGINT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE KEY uk_carbon_plan_version (building_id, plan_year, version_no),
 KEY idx_carbon_plan_active (building_id, plan_year, status)
);
