DROP TABLE IF EXISTS biz_meter_coverage_target;
DROP TABLE IF EXISTS biz_meter_coverage_revision;
CREATE TABLE biz_meter_coverage_revision (
  meter_id VARCHAR(32) NOT NULL, revision BIGINT NOT NULL, building_id VARCHAR(32) NOT NULL,
  effective_at BIGINT NOT NULL, installation_space_id VARCHAR(32), installation_space_name VARCHAR(100),
  scope_label VARCHAR(160) NOT NULL, reason VARCHAR(500) NOT NULL, created_by BIGINT NOT NULL,
  PRIMARY KEY (meter_id,revision), CHECK (revision>0)
);
CREATE TABLE biz_meter_coverage_target (
  meter_id VARCHAR(32) NOT NULL, revision BIGINT NOT NULL, building_id VARCHAR(32) NOT NULL,
  target_equipment_id VARCHAR(32) NOT NULL, equipment_code VARCHAR(50) NOT NULL,
  equipment_name VARCHAR(100) NOT NULL, space_id VARCHAR(32), space_name VARCHAR(100),
  PRIMARY KEY (meter_id,revision,target_equipment_id), CHECK(meter_id<>target_equipment_id),
  FOREIGN KEY(meter_id,revision) REFERENCES biz_meter_coverage_revision(meter_id,revision)
);
