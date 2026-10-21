-- 新档案只存在于所属关系草稿；审核发布前不进入运行资产、采集身份或别名。
CREATE TABLE IF NOT EXISTS biz_relation_staged_asset (
 staged_id VARCHAR(32) NOT NULL PRIMARY KEY,
 version_id VARCHAR(32) NOT NULL,
 building_id VARCHAR(32) NOT NULL,
 object_type VARCHAR(24) NOT NULL,
 object_id VARCHAR(80) NOT NULL,
 node_id VARCHAR(32) NOT NULL,
 object_code VARCHAR(100) NOT NULL,
 object_name VARCHAR(100) NOT NULL,
 space_id VARCHAR(32) NULL,
 system_group_id VARCHAR(32) NULL,
 equipment_id VARCHAR(32) NULL,
 definition_json LONGTEXT NOT NULL,
 definition_sha256 CHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'STAGED',
 created_by BIGINT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 published_at TIMESTAMP NULL,
 UNIQUE KEY uk_staged_object (object_type,object_id),
 UNIQUE KEY uk_staged_code (building_id,object_type,object_code),
 UNIQUE KEY uk_staged_node (node_id)
);
