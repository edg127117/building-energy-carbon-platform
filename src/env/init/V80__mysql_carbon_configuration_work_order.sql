ALTER TABLE biz_carbon_project_version ADD UNIQUE KEY uk_carbon_project_building_identity (version_id,building_id);
-- V77可能已在不同库默认排序规则下成功应用；外键列沿用父列定义，不重写成功版本或改动父表。
SET @carbon_command_building_charset = (
 SELECT CONCAT('CHARACTER SET ', CHARACTER_SET_NAME, ' COLLATE ', COLLATION_NAME)
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='biz_carbon_project_version' AND COLUMN_NAME='building_id'
);
SET @carbon_command_project_charset = (
 SELECT CONCAT('CHARACTER SET ', CHARACTER_SET_NAME, ' COLLATE ', COLLATION_NAME)
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='biz_carbon_project_version' AND COLUMN_NAME='version_id'
);
SET @carbon_command_ddl = CONCAT('CREATE TABLE biz_carbon_configuration_command (
 command_id VARCHAR(32) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) ', @carbon_command_building_charset, ' NOT NULL,
 project_version_id VARCHAR(32) ', @carbon_command_project_charset, ' NOT NULL,
 idempotency_key VARCHAR(80) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 guide_fingerprint CHAR(64) NOT NULL,
 receipt_json TEXT NOT NULL,
 created_by BIGINT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_carbon_configuration_command (building_id,project_version_id,idempotency_key),
 CONSTRAINT fk_carbon_command_project FOREIGN KEY (project_version_id,building_id)
  REFERENCES biz_carbon_project_version(version_id,building_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci');
PREPARE carbon_command_statement FROM @carbon_command_ddl;
EXECUTE carbon_command_statement;
DEALLOCATE PREPARE carbon_command_statement;
