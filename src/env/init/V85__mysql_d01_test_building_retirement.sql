-- D01 合成建筑只从常规目录和统计范围排除；建筑及关联证据保持原样。
-- 存量父列可能与库当前默认排序规则不同，外键列沿用父列定义，不改动父表。
SET @d01_retirement_building_charset = (
    SELECT CONCAT('CHARACTER SET ', CHARACTER_SET_NAME, ' COLLATE ', COLLATION_NAME)
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
      AND TABLE_NAME='building' AND COLUMN_NAME='building_id'
);
SET @d01_retirement_ddl = CONCAT('CREATE TABLE biz_test_building_retirement (
    building_id VARCHAR(32) ', @d01_retirement_building_charset, ' NOT NULL PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    exclusion_request_id VARCHAR(32) NOT NULL,
    excluded_at DATETIME(3) NOT NULL,
    archive_request_id VARCHAR(32) NULL,
    archived_at DATETIME(3) NULL,
    CONSTRAINT fk_test_building_retirement_building FOREIGN KEY (building_id)
        REFERENCES building (building_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_test_building_retirement_status CHECK (status IN (''EXCLUDED'', ''ARCHIVED''))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci');
PREPARE d01_retirement_statement FROM @d01_retirement_ddl;
EXECUTE d01_retirement_statement;
DEALLOCATE PREPARE d01_retirement_statement;
