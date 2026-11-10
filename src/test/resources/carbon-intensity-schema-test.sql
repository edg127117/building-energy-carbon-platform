CREATE TABLE IF NOT EXISTS biz_carbon_intensity_standard_version (
 version_id VARCHAR(32) PRIMARY KEY, catalog_id VARCHAR(32) NOT NULL, revision_no INT NOT NULL,
 standard_name VARCHAR(200) NOT NULL, document_version VARCHAR(100) NOT NULL, source_reference VARCHAR(1000) NOT NULL,
 published_on DATE, created_by BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 evidence_hash CHAR(64) NOT NULL, UNIQUE(catalog_id,revision_no)
);
CREATE TABLE IF NOT EXISTS biz_carbon_intensity_standard_entry (
 version_id VARCHAR(32) NOT NULL, entry_no INT NOT NULL, region_prefix VARCHAR(6) NOT NULL,
 building_category VARCHAR(30) NOT NULL,time_status VARCHAR(16) NOT NULL,applicable_from DATE,applicable_to DATE,
 rule_json VARCHAR(10000) NOT NULL, PRIMARY KEY(version_id,entry_no),
 FOREIGN KEY(version_id) REFERENCES biz_carbon_intensity_standard_version(version_id)
);
