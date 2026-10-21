-- 新档案只存在于所属关系草稿；审核发布前不进入运行资产、采集身份或别名。
CREATE TABLE biz_relation_staged_asset (
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
 UNIQUE KEY uk_staged_node (node_id),
 KEY idx_staged_version (version_id,status),
 CONSTRAINT fk_staged_version_building FOREIGN KEY (version_id,building_id)
   REFERENCES biz_relation_version(version_id,building_id) ON DELETE RESTRICT
-- 外键字符列须与V24父表采用相同排序规则，不能继承部署库可能不同的默认值。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE biz_relation_node DROP CHECK chk_relation_node_status;
ALTER TABLE biz_relation_node ADD CONSTRAINT chk_relation_node_status
 CHECK (status IN ('ACTIVE','RETIRED','STAGED'));
-- 旧设备外键保留；草稿可先指向暂存设备，发布时原子转换为正式外键。
ALTER TABLE biz_asset_assignment_version_item ADD COLUMN staged_equipment_id VARCHAR(32) NULL;
