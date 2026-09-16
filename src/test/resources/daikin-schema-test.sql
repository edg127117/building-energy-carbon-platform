DROP TABLE IF EXISTS biz_daikin_directory_sync_job;
DROP TABLE IF EXISTS biz_daikin_catalog_sync;
DROP TABLE IF EXISTS biz_daikin_directory;
DROP TABLE IF EXISTS biz_daikin_project_mapping_version;
DROP TABLE IF EXISTS biz_daikin_project_mapping;
DROP TABLE IF EXISTS biz_daikin_source;

CREATE TABLE biz_daikin_source (
  source_id VARCHAR(200) PRIMARY KEY,
  registered_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) NOT NULL
);

CREATE TABLE biz_daikin_project_mapping (
  source_id VARCHAR(200) NOT NULL,
  site_id VARCHAR(200) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  mapping_version INT NOT NULL,
  mapped_by BIGINT NOT NULL,
  mapped_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (source_id,site_id),
  CONSTRAINT fk_daikin_mapping_source_test FOREIGN KEY (source_id)
    REFERENCES biz_daikin_source(source_id),
  CONSTRAINT fk_daikin_mapping_building_test FOREIGN KEY (building_id)
    REFERENCES building(building_id)
);

CREATE TABLE biz_daikin_project_mapping_version (
  source_id VARCHAR(200) NOT NULL,
  site_id VARCHAR(200) NOT NULL,
  mapping_version INT NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  mapped_by BIGINT NOT NULL,
  mapped_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (source_id,site_id,mapping_version),
  CONSTRAINT fk_daikin_mapping_version_source_test FOREIGN KEY (source_id)
    REFERENCES biz_daikin_source(source_id),
  CONSTRAINT fk_daikin_mapping_version_building_test FOREIGN KEY (building_id)
    REFERENCES building(building_id)
);

CREATE TABLE biz_daikin_directory (
  pending_id VARCHAR(32) PRIMARY KEY,
  identity_hash CHAR(64) NOT NULL,
  source_id VARCHAR(200) NOT NULL,
  site_id VARCHAR(200) NOT NULL,
  controller_id VARCHAR(200) NOT NULL,
  device_kind VARCHAR(10) NOT NULL,
  unit_id VARCHAR(200) NOT NULL,
  equipment_id VARCHAR(200),
  site_name VARCHAR(500),
  device_name VARCHAR(500),
  observed_at TIMESTAMP(3) NOT NULL,
  last_catalog_at TIMESTAMP(3) NOT NULL,
  missing TINYINT NOT NULL,
  create_time TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) NOT NULL,
  UNIQUE (source_id,identity_hash),
  CONSTRAINT fk_daikin_directory_pending_test FOREIGN KEY (pending_id)
    REFERENCES biz_pending_device(pending_id),
  CONSTRAINT fk_daikin_directory_source_test FOREIGN KEY (source_id)
    REFERENCES biz_daikin_source(source_id),
  CONSTRAINT chk_daikin_directory_kind_test CHECK (device_kind IN ('INDOOR','OUTDOOR')),
  CONSTRAINT chk_daikin_directory_missing_test CHECK (missing IN (0,1))
);

CREATE TABLE biz_daikin_catalog_sync (
  source_id VARCHAR(200) NOT NULL,
  device_kind VARCHAR(10) NOT NULL,
  completed_at TIMESTAMP(3) NOT NULL,
  catalog_hash CHAR(64) NOT NULL,
  PRIMARY KEY (source_id,device_kind),
  CONSTRAINT fk_daikin_sync_source_test FOREIGN KEY (source_id)
    REFERENCES biz_daikin_source(source_id),
  CONSTRAINT chk_daikin_sync_kind_test CHECK (device_kind IN ('INDOOR','OUTDOOR'))
);
