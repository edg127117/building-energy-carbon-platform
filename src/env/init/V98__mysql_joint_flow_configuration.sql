-- 展示配置与选用指针独立存储，禁止修改正式分类有效期及历史核算。
CREATE TABLE biz_joint_flow_configuration (
 version_id VARCHAR(32) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 version_no INT NOT NULL,
 revision BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL,
 configuration_json LONGTEXT NOT NULL,
 configuration_hash CHAR(64) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_by BIGINT NOT NULL,
 reviewed_by BIGINT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT chk_joint_flow_status CHECK (status IN ('DRAFT','PENDING_REVIEW','APPROVED','REJECTED')),
 UNIQUE KEY uk_joint_flow_version (building_id,version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE biz_joint_flow_selection (
 building_id VARCHAR(32) NOT NULL PRIMARY KEY,
 version_id VARCHAR(32) NULL,
 revision BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
