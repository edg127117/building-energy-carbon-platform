-- 版本化表计结构：表计自身的层级、方向与专业确认独立于覆盖对象分配。
CREATE TABLE biz_meter_structure_version_item (
  structure_item_id VARCHAR(32) PRIMARY KEY,
  version_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  metering_boundary_id VARCHAR(32),
  meter_point_node_id VARCHAR(32) NOT NULL,
  meter_role VARCHAR(24) NOT NULL,
  parent_meter_point_node_id VARCHAR(32),
  meter_direction VARCHAR(24) NOT NULL,
  confirmation_status VARCHAR(24) NOT NULL,
  reason_code VARCHAR(64),
  reason_text VARCHAR(500),
  evidence_reference VARCHAR(500),
  description VARCHAR(500),
  source_type VARCHAR(24) NOT NULL,
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_meter_structure_version_point UNIQUE (version_id, meter_point_node_id),
  CONSTRAINT chk_meter_structure_role CHECK
    (meter_role IN ('MAIN','SUB','INDEPENDENT','UNKNOWN')),
  CONSTRAINT chk_meter_structure_direction CHECK
    (meter_direction IN ('INBOUND','OUTBOUND','BIDIRECTIONAL','UNKNOWN')),
  CONSTRAINT chk_meter_structure_confirmation CHECK
    (confirmation_status IN ('CONFIRMED','PENDING_EXPERT')),
  CONSTRAINT chk_meter_structure_source CHECK
    (source_type IN ('MANUAL','IMPORT','SYSTEM_MIGRATION')),
  CONSTRAINT chk_meter_structure_no_self CHECK
    (parent_meter_point_node_id IS NULL OR meter_point_node_id <> parent_meter_point_node_id),
  CONSTRAINT chk_meter_structure_parent_role CHECK
    ((meter_role='SUB' AND parent_meter_point_node_id IS NOT NULL)
      OR meter_role NOT IN ('SUB','MAIN','INDEPENDENT')
      OR (meter_role IN ('MAIN','INDEPENDENT') AND parent_meter_point_node_id IS NULL)),
  CONSTRAINT chk_meter_structure_unknown_pending CHECK
    ((meter_role <> 'UNKNOWN' AND meter_direction <> 'UNKNOWN')
      OR confirmation_status='PENDING_EXPERT'),
  CONSTRAINT chk_meter_structure_pending_reason CHECK
    (confirmation_status <> 'PENDING_EXPERT'
      OR reason_code IS NOT NULL OR reason_text IS NOT NULL OR evidence_reference IS NOT NULL),
  CONSTRAINT fk_meter_structure_version FOREIGN KEY (version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_meter_structure_version_building FOREIGN KEY (version_id, building_id)
    REFERENCES biz_relation_version (version_id, building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_meter_structure_boundary_building FOREIGN KEY (building_id, metering_boundary_id)
    REFERENCES biz_metering_boundary (building_id, boundary_id) ON DELETE RESTRICT,
  CONSTRAINT fk_meter_structure_point FOREIGN KEY (building_id, meter_point_node_id)
    REFERENCES biz_relation_node (building_id, node_id) ON DELETE RESTRICT,
  CONSTRAINT fk_meter_structure_parent FOREIGN KEY (building_id, parent_meter_point_node_id)
    REFERENCES biz_relation_node (building_id, node_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_meter_structure_version_parent
  ON biz_meter_structure_version_item (version_id, parent_meter_point_node_id);
CREATE INDEX idx_meter_structure_version_boundary
  ON biz_meter_structure_version_item (version_id, metering_boundary_id);
