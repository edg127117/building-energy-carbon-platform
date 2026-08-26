-- 空间、系统、设备、测点与计量边界关系治理。
-- 本迁移只创建治理结构；业务关系必须通过分建筑初始化和审核流程显式生成。

CREATE TABLE biz_relation_model (
  model_id VARCHAR(32) PRIMARY KEY,
  scope_type VARCHAR(20) NOT NULL,
  scope_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  governance_mode VARCHAR(20) NOT NULL DEFAULT 'LEGACY',
  active_version_id VARCHAR(32),
  draft_version_id VARCHAR(32),
  config_revision BIGINT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_relation_model_scope UNIQUE (scope_type, scope_id),
  CONSTRAINT uk_relation_model_building UNIQUE (building_id),
  CONSTRAINT chk_relation_model_scope CHECK (scope_type IN ('BUILDING','CAMPUS')),
  CONSTRAINT chk_relation_model_mode CHECK (governance_mode IN ('LEGACY','GOVERNED')),
  CONSTRAINT chk_relation_model_revision CHECK (config_revision >= 0),
  CONSTRAINT fk_relation_model_building FOREIGN KEY (building_id)
    REFERENCES building (building_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE biz_relation_version (
  version_id VARCHAR(32) PRIMARY KEY,
  model_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  version_no INT NOT NULL,
  base_version_id VARCHAR(32),
  copied_from_version_id VARCHAR(32),
  status VARCHAR(24) NOT NULL,
  config_revision BIGINT NOT NULL DEFAULT 0,
  submitted_revision BIGINT,
  snapshot_sha256 CHAR(64),
  change_reason VARCHAR(500) NOT NULL,
  created_by BIGINT NOT NULL,
  submitted_by BIGINT,
  approved_by BIGINT,
  activated_by BIGINT,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  submitted_at DATETIME(3),
  approved_at DATETIME(3),
  effective_at DATETIME(3),
  superseded_at DATETIME(3),
  CONSTRAINT uk_relation_version_no UNIQUE (model_id, version_no),
  CONSTRAINT uk_relation_version_building_identity UNIQUE (version_id, building_id),
  CONSTRAINT chk_relation_version_no CHECK (version_no > 0),
  CONSTRAINT chk_relation_version_status CHECK (status IN
    ('DRAFT','PENDING_REVIEW','APPROVED','EFFECTIVE','SUPERSEDED','REJECTED','WITHDRAWN')),
  CONSTRAINT chk_relation_version_revision CHECK (config_revision >= 0),
  CONSTRAINT fk_relation_version_model FOREIGN KEY (model_id)
    REFERENCES biz_relation_model (model_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_version_building FOREIGN KEY (building_id)
    REFERENCES building (building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_version_base FOREIGN KEY (base_version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_version_copy FOREIGN KEY (copied_from_version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE biz_relation_model
  ADD CONSTRAINT fk_relation_model_active_version FOREIGN KEY (active_version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_relation_model_draft_version FOREIGN KEY (draft_version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE RESTRICT;

CREATE INDEX idx_relation_version_building_status
  ON biz_relation_version (building_id, status, version_no);

CREATE TABLE biz_metering_boundary (
  boundary_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  boundary_code VARCHAR(80) NOT NULL,
  boundary_name VARCHAR(160) NOT NULL,
  energy_type VARCHAR(64),
  confirmation_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_EXPERT',
  evidence_reference VARCHAR(500),
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_metering_boundary_code UNIQUE (building_id, boundary_code),
  CONSTRAINT uk_metering_boundary_identity UNIQUE (building_id, boundary_id),
  CONSTRAINT chk_metering_boundary_confirmation CHECK
    (confirmation_status IN ('CONFIRMED','PENDING_EXPERT')),
  CONSTRAINT chk_metering_boundary_status CHECK (status IN ('ACTIVE','RETIRED')),
  CONSTRAINT fk_metering_boundary_building FOREIGN KEY (building_id)
    REFERENCES building (building_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE biz_relation_node (
  node_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  node_type VARCHAR(24) NOT NULL,
  business_object_id VARCHAR(32) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_relation_node_object UNIQUE (building_id, node_type, business_object_id),
  CONSTRAINT uk_relation_node_building_identity UNIQUE (building_id, node_id),
  CONSTRAINT chk_relation_node_type CHECK
    (node_type IN ('SPACE','SYSTEM','EQUIPMENT','POINT','METERING_BOUNDARY')),
  CONSTRAINT chk_relation_node_status CHECK (status IN ('ACTIVE','RETIRED')),
  CONSTRAINT fk_relation_node_building FOREIGN KEY (building_id)
    REFERENCES building (building_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE biz_space_parent_version_item (
  version_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  space_id VARCHAR(32) NOT NULL,
  parent_space_id VARCHAR(32),
  sort_order INT NOT NULL DEFAULT 0,
  PRIMARY KEY (version_id, space_id),
  CONSTRAINT fk_relation_space_version FOREIGN KEY (version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_space_version_building FOREIGN KEY (version_id, building_id)
    REFERENCES biz_relation_version (version_id, building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_space_object FOREIGN KEY (space_id, building_id)
    REFERENCES biz_space (space_id, building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_space_parent FOREIGN KEY (parent_space_id, building_id)
    REFERENCES biz_space (space_id, building_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE biz_asset_assignment_version_item (
  version_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  object_type VARCHAR(20) NOT NULL,
  object_id VARCHAR(32) NOT NULL,
  space_id VARCHAR(32),
  system_group_id VARCHAR(32),
  equipment_id VARCHAR(32),
  source_type VARCHAR(24) NOT NULL,
  PRIMARY KEY (version_id, object_type, object_id),
  CONSTRAINT chk_relation_assignment_object CHECK (object_type IN ('EQUIPMENT','POINT')),
  CONSTRAINT chk_relation_assignment_source CHECK
    (source_type IN ('MANUAL','IMPORT','SYSTEM_MIGRATION','LEGACY_PROJECTION')),
  CONSTRAINT fk_relation_assignment_version FOREIGN KEY (version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_assignment_version_building FOREIGN KEY (version_id, building_id)
    REFERENCES biz_relation_version (version_id, building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_assignment_space FOREIGN KEY (space_id, building_id)
    REFERENCES biz_space (space_id, building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_assignment_system FOREIGN KEY (system_group_id, building_id)
    REFERENCES biz_system_group (system_group_id, building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_assignment_equipment FOREIGN KEY (equipment_id, building_id)
    REFERENCES biz_equipment (equip_id, building_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE biz_semantic_relation_version_item (
  relation_item_id VARCHAR(32) PRIMARY KEY,
  version_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  relation_type VARCHAR(32) NOT NULL,
  source_node_id VARCHAR(32) NOT NULL,
  target_node_id VARCHAR(32) NOT NULL,
  source_type VARCHAR(24) NOT NULL,
  evidence_reference VARCHAR(500),
  confirmation_status VARCHAR(24) NOT NULL,
  description VARCHAR(500),
  create_by BIGINT NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_semantic_relation_edge UNIQUE
    (version_id, relation_type, source_node_id, target_node_id),
  CONSTRAINT chk_semantic_relation_type CHECK
    (relation_type IN ('SERVES','MEASURES','CONNECTED_TO','SUPPLIES','RETURNS')),
  CONSTRAINT chk_semantic_relation_source CHECK
    (source_type IN ('MANUAL','IMPORT','SYSTEM_MIGRATION')),
  CONSTRAINT chk_semantic_relation_confirmation CHECK
    (confirmation_status IN ('CONFIRMED','PENDING_EXPERT')),
  CONSTRAINT chk_semantic_relation_no_self CHECK (source_node_id <> target_node_id),
  CONSTRAINT fk_semantic_relation_version FOREIGN KEY (version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_semantic_relation_version_building FOREIGN KEY (version_id, building_id)
    REFERENCES biz_relation_version (version_id, building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_semantic_relation_source_node FOREIGN KEY (building_id, source_node_id)
    REFERENCES biz_relation_node (building_id, node_id) ON DELETE RESTRICT,
  CONSTRAINT fk_semantic_relation_target_node FOREIGN KEY (building_id, target_node_id)
    REFERENCES biz_relation_node (building_id, node_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_semantic_relation_source
  ON biz_semantic_relation_version_item (version_id, source_node_id);
CREATE INDEX idx_semantic_relation_target
  ON biz_semantic_relation_version_item (version_id, target_node_id);

CREATE TABLE biz_metering_assignment_version_item (
  assignment_item_id VARCHAR(32) PRIMARY KEY,
  version_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  metering_boundary_id VARCHAR(32),
  meter_point_node_id VARCHAR(32),
  target_node_id VARCHAR(32),
  allocation_status VARCHAR(24) NOT NULL,
  reason_code VARCHAR(64),
  reason_text VARCHAR(500),
  evidence_reference VARCHAR(500),
  create_by BIGINT NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_metering_assignment_status CHECK
    (allocation_status IN ('ASSIGNED','UNASSIGNED','PENDING_EXPERT','INVALID')),
  CONSTRAINT fk_metering_assignment_version FOREIGN KEY (version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_metering_assignment_version_building FOREIGN KEY (version_id, building_id)
    REFERENCES biz_relation_version (version_id, building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_metering_assignment_boundary FOREIGN KEY (metering_boundary_id)
    REFERENCES biz_metering_boundary (boundary_id) ON DELETE RESTRICT,
  CONSTRAINT fk_metering_assignment_boundary_building FOREIGN KEY (building_id, metering_boundary_id)
    REFERENCES biz_metering_boundary (building_id, boundary_id) ON DELETE RESTRICT,
  CONSTRAINT fk_metering_assignment_point_node FOREIGN KEY (building_id, meter_point_node_id)
    REFERENCES biz_relation_node (building_id, node_id) ON DELETE RESTRICT,
  CONSTRAINT fk_metering_assignment_target_node FOREIGN KEY (building_id, target_node_id)
    REFERENCES biz_relation_node (building_id, node_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_metering_assignment_version_status
  ON biz_metering_assignment_version_item (version_id, allocation_status);

CREATE TABLE biz_relation_review_request (
  request_id VARCHAR(32) PRIMARY KEY,
  version_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  request_no INT NOT NULL,
  submitted_revision BIGINT NOT NULL,
  snapshot_sha256 CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  submitted_by BIGINT NOT NULL,
  reviewer_id BIGINT,
  review_reason VARCHAR(500),
  self_approval_dev_mode TINYINT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(160),
  request_sha256 CHAR(64),
  submitted_at DATETIME(3) NOT NULL,
  reviewed_at DATETIME(3),
  withdrawn_at DATETIME(3),
  CONSTRAINT uk_relation_review_no UNIQUE (version_id, request_no),
  CONSTRAINT uk_relation_review_idempotency UNIQUE (idempotency_key),
  CONSTRAINT chk_relation_review_status CHECK
    (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT chk_relation_review_self_approval CHECK (self_approval_dev_mode IN (0,1)),
  CONSTRAINT fk_relation_review_version FOREIGN KEY (version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_relation_review_version_building FOREIGN KEY (version_id, building_id)
    REFERENCES biz_relation_version (version_id, building_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_relation_review_building_status
  ON biz_relation_review_request (building_id, status, submitted_at);

CREATE TABLE biz_relation_validation_issue (
  issue_id VARCHAR(32) PRIMARY KEY,
  version_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  issue_level VARCHAR(24) NOT NULL,
  issue_code VARCHAR(64) NOT NULL,
  object_type VARCHAR(32),
  object_id VARCHAR(32),
  message VARCHAR(500) NOT NULL,
  detected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_relation_issue_level CHECK
    (issue_level IN ('ERROR','PENDING_EXPERT','WARNING')),
  CONSTRAINT fk_relation_issue_version FOREIGN KEY (version_id)
    REFERENCES biz_relation_version (version_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_relation_issue_version_level
  ON biz_relation_validation_issue (version_id, issue_level, issue_code);

CREATE TABLE biz_relation_audit_log (
  audit_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  operator_id BIGINT NOT NULL,
  action_type VARCHAR(64) NOT NULL,
  object_type VARCHAR(32) NOT NULL,
  object_id VARCHAR(32) NOT NULL,
  version_id VARCHAR(32),
  request_id VARCHAR(32),
  before_state VARCHAR(32),
  after_state VARCHAR(32),
  reason VARCHAR(500),
  result VARCHAR(20) NOT NULL,
  summary VARCHAR(1000),
  idempotency_key VARCHAR(160),
  request_sha256 CHAR(64),
  operation_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_relation_audit_idempotency UNIQUE (idempotency_key),
  CONSTRAINT chk_relation_audit_result CHECK (result IN ('SUCCESS','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_relation_audit_building_time
  ON biz_relation_audit_log (building_id, operation_time);
