-- 表计现场档案独立于能源计量治理。共同覆盖不是分摊权重，也不授予跨表汇总语义。
CREATE TABLE biz_meter_coverage_revision (
  meter_id VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  effective_at BIGINT NOT NULL,
  installation_space_id VARCHAR(32),
  installation_space_name VARCHAR(100),
  scope_label VARCHAR(160) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_by BIGINT NOT NULL,
  PRIMARY KEY (meter_id, revision),
  CONSTRAINT chk_meter_coverage_revision CHECK (revision > 0),
  CONSTRAINT fk_meter_coverage_equipment FOREIGN KEY (meter_id) REFERENCES biz_equipment(equip_id),
  CONSTRAINT fk_meter_coverage_building FOREIGN KEY (building_id) REFERENCES building(building_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE biz_meter_coverage_target (
  meter_id VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  target_equipment_id VARCHAR(32) NOT NULL,
  equipment_code VARCHAR(50) NOT NULL,
  equipment_name VARCHAR(100) NOT NULL,
  space_id VARCHAR(32),
  space_name VARCHAR(100),
  PRIMARY KEY (meter_id, revision, target_equipment_id),
  CONSTRAINT fk_meter_coverage_target_revision FOREIGN KEY (meter_id,revision)
    REFERENCES biz_meter_coverage_revision(meter_id,revision),
  CONSTRAINT fk_meter_coverage_target_equipment FOREIGN KEY (target_equipment_id) REFERENCES biz_equipment(equip_id),
  CONSTRAINT chk_meter_coverage_no_self CHECK (meter_id <> target_equipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_meter_coverage_target ON biz_meter_coverage_target(target_equipment_id);

-- 仅纠正已确认电表产品的分类。保留存量设备/测点编码及身份，避免历史时序标签断链。
INSERT INTO biz_equipment_type
  (type_code,type_name,asset_code_prefix,equip_category,standard_source,status)
VALUES ('ELECTRIC_METER_1P','单相电表','EM1P','ELECTRIC_METER','METER_ARCHIVE_V1',1),
       ('ELECTRIC_METER_3P','三相电表','EM3P','ELECTRIC_METER','METER_ARCHIVE_V1',1);
INSERT INTO biz_point_naming_rule
  (rule_id,standard_version,family_code,component_code,code_template,standard_source,status)
VALUES ('RULE_EM1P_MAIN','METER_ARCHIVE_V1','ELECTRIC_METER_1P','MAIN','EM1P[n]','METER_ARCHIVE_V1',1),
       ('RULE_EM3P_MAIN','METER_ARCHIVE_V1','ELECTRIC_METER_3P','MAIN','EM3P[n]','METER_ARCHIVE_V1',1);

UPDATE biz_device_product SET equipment_type_code='ELECTRIC_METER_1P'
WHERE product_id='PRODUCT_IDU_METER_1039';
UPDATE biz_device_product SET equipment_type_code='ELECTRIC_METER_3P'
WHERE product_id='PRODUCT_ODU_METER_339';
UPDATE biz_equipment SET type_code='ELECTRIC_METER_1P',equip_category='ELECTRIC_METER'
WHERE product_id='PRODUCT_IDU_METER_1039';
UPDATE biz_equipment SET type_code='ELECTRIC_METER_3P',equip_category='ELECTRIC_METER'
WHERE product_id='PRODUCT_ODU_METER_339';
UPDATE biz_equipment SET equip_name=REPLACE(equip_name,'单项电表','单相电表')
WHERE product_id='PRODUCT_IDU_METER_1039' AND equip_name LIKE '单项电表%';
UPDATE biz_equipment SET equip_name=REPLACE(equip_name,'三项电表','三相电表')
WHERE product_id='PRODUCT_ODU_METER_339' AND equip_name LIKE '三项电表%';

-- 不从原来的通用空间推断表计安装位置，不从设备名称推断覆盖清单，不回填历史版本。
