-- 标准设备参数读取与参数版本治理闭环的 MySQL 8 增量结构。
-- 本脚本只建立可配置治理框架，不预置任何未经能源专家确认的参数、单位、边界或厂家映射。

CREATE TABLE IF NOT EXISTS biz_device_parameter_unit (
  unit_code VARCHAR(20) PRIMARY KEY,
  quantity_kind VARCHAR(50) NOT NULL,
  unit_symbol VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  evidence_reference VARCHAR(255) NOT NULL,
  config_revision INT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  CONSTRAINT chk_device_parameter_unit_status CHECK (status IN ('DRAFT','ENABLED','DISABLED')),
  CONSTRAINT chk_device_parameter_unit_revision CHECK (config_revision >= 0)
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_definition (
  definition_id VARCHAR(32) PRIMARY KEY,
  parameter_code VARCHAR(50) NOT NULL,
  parameter_name VARCHAR(100) NOT NULL,
  business_definition VARCHAR(500) NOT NULL,
  quantity_kind VARCHAR(50) NOT NULL,
  value_type VARCHAR(20) NOT NULL DEFAULT 'DECIMAL',
  standard_unit VARCHAR(20) NOT NULL,
  storage_scale INT NOT NULL,
  display_scale INT NOT NULL,
  evidence_reference VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL,
  config_revision INT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  CONSTRAINT uk_device_parameter_definition_code UNIQUE (parameter_code),
  CONSTRAINT chk_device_parameter_definition_type CHECK (value_type = 'DECIMAL'),
  CONSTRAINT chk_device_parameter_definition_status CHECK (status IN ('DRAFT','ENABLED','DISABLED')),
  CONSTRAINT chk_device_parameter_definition_scale CHECK (
    storage_scale BETWEEN 0 AND 12 AND display_scale BETWEEN 0 AND storage_scale
  ),
  CONSTRAINT fk_device_parameter_definition_unit
    FOREIGN KEY (standard_unit) REFERENCES biz_device_parameter_unit (unit_code) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_applicability (
  applicability_id VARCHAR(32) PRIMARY KEY,
  equipment_type_code VARCHAR(20) NOT NULL,
  definition_id VARCHAR(32) NOT NULL,
  required_flag TINYINT NOT NULL DEFAULT 0,
  formula_readable TINYINT NOT NULL DEFAULT 0,
  hard_min DECIMAL(30,12),
  hard_max DECIMAL(30,12),
  warning_min DECIMAL(30,12),
  warning_max DECIMAL(30,12),
  comparison_tolerance DECIMAL(30,12),
  evidence_reference VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL,
  config_revision INT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  INDEX idx_device_parameter_applicability_type_status (equipment_type_code, status),
  CONSTRAINT uk_device_parameter_applicability UNIQUE (equipment_type_code, definition_id),
  CONSTRAINT chk_device_parameter_applicability_flags CHECK (
    required_flag IN (0,1) AND formula_readable IN (0,1)
  ),
  CONSTRAINT chk_device_parameter_applicability_status CHECK (status IN ('DRAFT','ENABLED','DISABLED')),
  CONSTRAINT chk_device_parameter_applicability_hard CHECK (hard_min IS NULL OR hard_max IS NULL OR hard_min <= hard_max),
  CONSTRAINT chk_device_parameter_applicability_warning CHECK (warning_min IS NULL OR warning_max IS NULL OR warning_min <= warning_max),
  CONSTRAINT chk_device_parameter_applicability_tolerance CHECK (comparison_tolerance IS NULL OR comparison_tolerance >= 0),
  CONSTRAINT fk_device_parameter_applicability_type
    FOREIGN KEY (equipment_type_code) REFERENCES biz_equipment_type (type_code) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_applicability_definition
    FOREIGN KEY (definition_id) REFERENCES biz_device_parameter_definition (definition_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_mapping (
  mapping_id VARCHAR(32) PRIMARY KEY,
  profile_code VARCHAR(50) NOT NULL,
  source_path VARCHAR(255) NOT NULL,
  current_active_version_id VARCHAR(32),
  config_revision INT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  CONSTRAINT uk_device_parameter_mapping_identity UNIQUE (profile_code, source_path)
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_mapping_version (
  mapping_version_id VARCHAR(32) PRIMARY KEY,
  mapping_id VARCHAR(32) NOT NULL,
  version_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  profile_version VARCHAR(50) NOT NULL,
  definition_id VARCHAR(32) NOT NULL,
  source_unit VARCHAR(20) NOT NULL,
  scale_value DECIMAL(30,12) NOT NULL,
  offset_value DECIMAL(30,12) NOT NULL,
  required_flag TINYINT NOT NULL DEFAULT 0,
  base_version_id VARCHAR(32),
  copied_from_version_id VARCHAR(32),
  change_reason VARCHAR(500) NOT NULL,
  evidence_reference VARCHAR(255) NOT NULL,
  created_by BIGINT NOT NULL,
  published_by BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  published_at TIMESTAMP(3),
  INDEX idx_device_parameter_mapping_version_status (mapping_id, status, version_no),
  CONSTRAINT uk_device_parameter_mapping_version UNIQUE (mapping_id, version_no),
  CONSTRAINT chk_device_parameter_mapping_version_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT chk_device_parameter_mapping_version_required CHECK (required_flag IN (0,1)),
  CONSTRAINT fk_device_parameter_mapping_version_mapping
    FOREIGN KEY (mapping_id) REFERENCES biz_device_parameter_mapping (mapping_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_mapping_version_definition
    FOREIGN KEY (definition_id) REFERENCES biz_device_parameter_definition (definition_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_mapping_version_unit
    FOREIGN KEY (source_unit) REFERENCES biz_device_parameter_unit (unit_code) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_product_parameter_template (
  template_id VARCHAR(32) PRIMARY KEY,
  product_id VARCHAR(32) NOT NULL,
  current_active_revision_id VARCHAR(32),
  config_revision INT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  CONSTRAINT uk_product_parameter_template_product UNIQUE (product_id),
  CONSTRAINT fk_product_parameter_template_product
    FOREIGN KEY (product_id) REFERENCES biz_device_product (product_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_product_parameter_template_revision (
  template_revision_id VARCHAR(32) PRIMARY KEY,
  template_id VARCHAR(32) NOT NULL,
  revision_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  evidence_reference VARCHAR(255) NOT NULL,
  created_by BIGINT NOT NULL,
  published_by BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  published_at TIMESTAMP(3),
  CONSTRAINT uk_product_parameter_template_revision UNIQUE (template_id, revision_no),
  CONSTRAINT chk_product_parameter_template_revision_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT fk_product_parameter_template_revision_template
    FOREIGN KEY (template_id) REFERENCES biz_product_parameter_template (template_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_product_parameter_template_value (
  template_value_id VARCHAR(32) PRIMARY KEY,
  template_revision_id VARCHAR(32) NOT NULL,
  definition_id VARCHAR(32) NOT NULL,
  raw_value DECIMAL(30,12) NOT NULL,
  raw_unit VARCHAR(20) NOT NULL,
  normalized_value DECIMAL(30,12) NOT NULL,
  source_reference VARCHAR(255) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  CONSTRAINT uk_product_parameter_template_value UNIQUE (template_revision_id, definition_id),
  CONSTRAINT fk_product_parameter_template_value_revision
    FOREIGN KEY (template_revision_id) REFERENCES biz_product_parameter_template_revision (template_revision_id) ON DELETE RESTRICT,
  CONSTRAINT fk_product_parameter_template_value_definition
    FOREIGN KEY (definition_id) REFERENCES biz_device_parameter_definition (definition_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_candidate (
  candidate_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  definition_id VARCHAR(32),
  source_type VARCHAR(20) NOT NULL,
  source_reference VARCHAR(255) NOT NULL,
  source_version VARCHAR(100),
  source_slot_key VARCHAR(160),
  source_parameter_key VARCHAR(255) NOT NULL,
  raw_value VARCHAR(100) NOT NULL,
  raw_unit VARCHAR(20),
  normalized_value DECIMAL(30,12),
  standard_unit VARCHAR(20),
  mapping_version_id VARCHAR(32),
  observed_at TIMESTAMP(3),
  validation_status VARCHAR(20) NOT NULL,
  validation_reason VARCHAR(64),
  warning_flag TINYINT NOT NULL DEFAULT 0,
  payload_hash CHAR(64) NOT NULL,
  current_flag TINYINT NOT NULL DEFAULT 0,
  supersedes_candidate_id VARCHAR(32),
  first_seen_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  last_seen_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  created_by BIGINT,
  INDEX idx_device_parameter_candidate_current
    (equip_id, definition_id, current_flag, validation_status, source_type),
  INDEX idx_device_parameter_candidate_problem
    (building_id, validation_status, last_seen_at),
  CONSTRAINT uk_device_parameter_candidate_idempotency UNIQUE
    (equip_id, source_type, source_parameter_key, source_version, payload_hash),
  CONSTRAINT chk_device_parameter_candidate_source CHECK (source_type IN ('DEVICE','MANUAL','EXCEL','TEMPLATE','LEGACY_MIGRATION')),
  CONSTRAINT chk_device_parameter_candidate_validation CHECK (validation_status IN ('READY','UNMAPPED','INVALID')),
  CONSTRAINT chk_device_parameter_candidate_flags CHECK (warning_flag IN (0,1) AND current_flag IN (0,1)),
  CONSTRAINT fk_device_parameter_candidate_building
    FOREIGN KEY (building_id) REFERENCES building (building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_candidate_equipment
    FOREIGN KEY (equip_id) REFERENCES biz_equipment (equip_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_candidate_definition
    FOREIGN KEY (definition_id) REFERENCES biz_device_parameter_definition (definition_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_candidate_unit
    FOREIGN KEY (standard_unit) REFERENCES biz_device_parameter_unit (unit_code) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_candidate_mapping
    FOREIGN KEY (mapping_version_id) REFERENCES biz_device_parameter_mapping_version (mapping_version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_candidate_supersedes
    FOREIGN KEY (supersedes_candidate_id) REFERENCES biz_device_parameter_candidate (candidate_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_conflict (
  conflict_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  definition_id VARCHAR(32) NOT NULL,
  status VARCHAR(20) NOT NULL,
  config_revision INT NOT NULL DEFAULT 0,
  selected_candidate_id VARCHAR(32),
  resolution_reason VARCHAR(500),
  resolved_by BIGINT,
  resolved_at TIMESTAMP(3),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  obsolete_at TIMESTAMP(3),
  INDEX idx_device_parameter_conflict_current (equip_id, definition_id, status, create_time),
  CONSTRAINT chk_device_parameter_conflict_status CHECK (status IN ('OPEN','RESOLVED','OBSOLETE')),
  CONSTRAINT fk_device_parameter_conflict_building
    FOREIGN KEY (building_id) REFERENCES building (building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_conflict_equipment
    FOREIGN KEY (equip_id) REFERENCES biz_equipment (equip_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_conflict_definition
    FOREIGN KEY (definition_id) REFERENCES biz_device_parameter_definition (definition_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_conflict_selected
    FOREIGN KEY (selected_candidate_id) REFERENCES biz_device_parameter_candidate (candidate_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_conflict_member (
  conflict_id VARCHAR(32) NOT NULL,
  candidate_id VARCHAR(32) NOT NULL,
  PRIMARY KEY (conflict_id, candidate_id),
  CONSTRAINT fk_device_parameter_conflict_member_conflict
    FOREIGN KEY (conflict_id) REFERENCES biz_device_parameter_conflict (conflict_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_conflict_member_candidate
    FOREIGN KEY (candidate_id) REFERENCES biz_device_parameter_candidate (candidate_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_set (
  parameter_set_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  current_timeline_revision_id VARCHAR(32),
  config_revision INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  CONSTRAINT uk_device_parameter_set_equipment UNIQUE (equip_id),
  CONSTRAINT fk_device_parameter_set_building
    FOREIGN KEY (building_id) REFERENCES building (building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_set_equipment
    FOREIGN KEY (equip_id) REFERENCES biz_equipment (equip_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_version (
  version_id VARCHAR(32) PRIMARY KEY,
  parameter_set_id VARCHAR(32) NOT NULL,
  version_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  open_marker TINYINT,
  config_revision INT NOT NULL DEFAULT 0,
  submitted_revision INT,
  base_version_id VARCHAR(32),
  base_timeline_revision_id VARCHAR(32),
  copied_from_version_id VARCHAR(32),
  change_type VARCHAR(20) NOT NULL,
  requested_effective_from TIMESTAMP(3),
  requested_effective_to TIMESTAMP(3),
  change_reason VARCHAR(500) NOT NULL,
  evidence_reference VARCHAR(255) NOT NULL,
  owner_user_id BIGINT NOT NULL,
  rollback_initiator_id BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  published_at TIMESTAMP(3),
  INDEX idx_device_parameter_version_status (parameter_set_id, status, version_no),
  CONSTRAINT uk_device_parameter_version_no UNIQUE (parameter_set_id, version_no),
  CONSTRAINT uk_device_parameter_version_open UNIQUE (parameter_set_id, open_marker),
  CONSTRAINT chk_device_parameter_version_status CHECK (status IN ('DRAFT','PENDING_REVIEW','PUBLISHED','REJECTED','CANCELLED')),
  CONSTRAINT chk_device_parameter_version_change CHECK (change_type IN ('INITIAL','UPDATE','ROLLBACK','CLEAR','MIGRATION')),
  CONSTRAINT chk_device_parameter_version_marker CHECK (open_marker IS NULL OR open_marker = 1),
  CONSTRAINT fk_device_parameter_version_set
    FOREIGN KEY (parameter_set_id) REFERENCES biz_device_parameter_set (parameter_set_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_version_base
    FOREIGN KEY (base_version_id) REFERENCES biz_device_parameter_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_version_copied
    FOREIGN KEY (copied_from_version_id) REFERENCES biz_device_parameter_version (version_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_version_value (
  version_value_id VARCHAR(32) PRIMARY KEY,
  version_id VARCHAR(32) NOT NULL,
  definition_id VARCHAR(32) NOT NULL,
  value_status VARCHAR(20) NOT NULL,
  normalized_value DECIMAL(30,12),
  standard_unit VARCHAR(20) NOT NULL,
  selected_candidate_id VARCHAR(32),
  source_type VARCHAR(20),
  source_reference VARCHAR(255),
  source_version VARCHAR(100),
  observed_at TIMESTAMP(3),
  missing_reason VARCHAR(500),
  warning_reason VARCHAR(500),
  CONSTRAINT uk_device_parameter_version_value UNIQUE (version_id, definition_id),
  CONSTRAINT chk_device_parameter_version_value_status CHECK (value_status IN ('VALUE','NOT_CONFIGURED')),
  CONSTRAINT chk_device_parameter_version_value_source CHECK (
    source_type IS NULL OR source_type IN ('DEVICE','MANUAL','EXCEL','TEMPLATE','LEGACY_MIGRATION')
  ),
  CONSTRAINT chk_device_parameter_version_value_shape CHECK (
    (value_status = 'VALUE' AND normalized_value IS NOT NULL AND selected_candidate_id IS NOT NULL AND source_type IS NOT NULL)
    OR (value_status = 'NOT_CONFIGURED' AND normalized_value IS NULL AND selected_candidate_id IS NULL AND missing_reason IS NOT NULL)
  ),
  CONSTRAINT fk_device_parameter_version_value_version
    FOREIGN KEY (version_id) REFERENCES biz_device_parameter_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_version_value_definition
    FOREIGN KEY (definition_id) REFERENCES biz_device_parameter_definition (definition_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_version_value_candidate
    FOREIGN KEY (selected_candidate_id) REFERENCES biz_device_parameter_candidate (candidate_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_review_request (
  request_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  version_id VARCHAR(32) NOT NULL,
  request_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  submitted_revision INT NOT NULL,
  snapshot_json JSON NOT NULL,
  snapshot_sha256 CHAR(64) NOT NULL,
  submitted_by BIGINT NOT NULL,
  submitted_at TIMESTAMP(3) NOT NULL,
  reviewer_id BIGINT,
  review_comment VARCHAR(500),
  reviewed_at TIMESTAMP(3),
  withdrawn_by BIGINT,
  withdrawn_at TIMESTAMP(3),
  idempotency_key VARCHAR(160),
  request_sha256 CHAR(64),
  decision_idempotency_key VARCHAR(160),
  decision_request_sha256 CHAR(64),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  INDEX idx_device_parameter_review_building_status (building_id, status, submitted_at),
  CONSTRAINT uk_device_parameter_review_no UNIQUE (version_id, request_no),
  CONSTRAINT uk_device_parameter_review_idempotency UNIQUE (idempotency_key),
  CONSTRAINT uk_device_parameter_review_decision_idempotency UNIQUE (decision_idempotency_key),
  CONSTRAINT chk_device_parameter_review_status CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT fk_device_parameter_review_version
    FOREIGN KEY (version_id) REFERENCES biz_device_parameter_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_review_building
    FOREIGN KEY (building_id) REFERENCES building (building_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_timeline_revision (
  timeline_revision_id VARCHAR(32) PRIMARY KEY,
  parameter_set_id VARCHAR(32) NOT NULL,
  revision_no INT NOT NULL,
  published_at TIMESTAMP(3) NOT NULL,
  published_by BIGINT NOT NULL,
  review_request_id VARCHAR(32) NOT NULL,
  publish_type VARCHAR(20) NOT NULL,
  retroactive_reason VARCHAR(500),
  evidence_reference VARCHAR(255) NOT NULL,
  recalculation_status VARCHAR(30) NOT NULL,
  impact_snapshot_json JSON NOT NULL,
  INDEX idx_device_parameter_timeline_knowledge (parameter_set_id, published_at, revision_no),
  CONSTRAINT uk_device_parameter_timeline_revision UNIQUE (parameter_set_id, revision_no),
  CONSTRAINT chk_device_parameter_timeline_publish_type CHECK (publish_type IN ('IMMEDIATE','RETROACTIVE')),
  CONSTRAINT chk_device_parameter_timeline_recalc CHECK (recalculation_status IN ('NOT_REQUIRED','PENDING_RECALC','RECALCULATING','SUCCEEDED','RECALC_FAILED')),
  CONSTRAINT fk_device_parameter_timeline_set
    FOREIGN KEY (parameter_set_id) REFERENCES biz_device_parameter_set (parameter_set_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_timeline_review
    FOREIGN KEY (review_request_id) REFERENCES biz_device_parameter_review_request (request_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_timeline_segment (
  timeline_segment_id VARCHAR(32) PRIMARY KEY,
  timeline_revision_id VARCHAR(32) NOT NULL,
  business_effective_from TIMESTAMP(3) NOT NULL,
  business_effective_to TIMESTAMP(3),
  version_id VARCHAR(32) NOT NULL,
  INDEX idx_device_parameter_timeline_segment_range
    (timeline_revision_id, business_effective_from, business_effective_to),
  CONSTRAINT uk_device_parameter_timeline_segment UNIQUE (timeline_revision_id, business_effective_from),
  CONSTRAINT chk_device_parameter_timeline_range CHECK (business_effective_to IS NULL OR business_effective_from < business_effective_to),
  CONSTRAINT fk_device_parameter_timeline_segment_revision
    FOREIGN KEY (timeline_revision_id) REFERENCES biz_device_parameter_timeline_revision (timeline_revision_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_timeline_segment_version
    FOREIGN KEY (version_id) REFERENCES biz_device_parameter_version (version_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_import_batch (
  import_batch_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  safe_file_name VARCHAR(255) NOT NULL,
  file_sha256 CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  row_count INT NOT NULL DEFAULT 0,
  valid_row_count INT NOT NULL DEFAULT 0,
  error_count INT NOT NULL DEFAULT 0,
  error_summary VARCHAR(1000),
  operator_id BIGINT NOT NULL,
  confirmed_by BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  confirmed_at TIMESTAMP(3),
  CONSTRAINT uk_device_parameter_import_hash UNIQUE (building_id, file_sha256),
  CONSTRAINT chk_device_parameter_import_status CHECK (status IN ('VALIDATING','VALIDATED','REJECTED','IMPORTED')),
  CONSTRAINT fk_device_parameter_import_building
    FOREIGN KEY (building_id) REFERENCES building (building_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_import_row (
  import_row_id VARCHAR(32) PRIMARY KEY,
  import_batch_id VARCHAR(32) NOT NULL,
  row_no INT NOT NULL,
  equipment_code VARCHAR(50),
  parameter_code VARCHAR(50),
  raw_value VARCHAR(100),
  raw_unit VARCHAR(20),
  observed_at TIMESTAMP(3),
  source_reference VARCHAR(255),
  validation_status VARCHAR(20) NOT NULL,
  error_field VARCHAR(50),
  error_code VARCHAR(64),
  CONSTRAINT uk_device_parameter_import_row UNIQUE (import_batch_id, row_no),
  CONSTRAINT chk_device_parameter_import_row_status CHECK (validation_status IN ('READY','INVALID')),
  CONSTRAINT fk_device_parameter_import_row_batch
    FOREIGN KEY (import_batch_id) REFERENCES biz_device_parameter_import_batch (import_batch_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_recalc_job (
  job_id VARCHAR(32) PRIMARY KEY,
  idempotency_key VARCHAR(160) NOT NULL,
  timeline_revision_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  indicator_ids_json JSON NOT NULL,
  from_minute TIMESTAMP(3) NOT NULL,
  to_minute TIMESTAMP(3) NOT NULL,
  status VARCHAR(20) NOT NULL,
  cursor_minute TIMESTAMP(3) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1000),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  finished_at TIMESTAMP(3),
  INDEX idx_device_parameter_recalc_status (status, update_time, job_id),
  CONSTRAINT uk_device_parameter_recalc_idempotency UNIQUE (idempotency_key),
  CONSTRAINT chk_device_parameter_recalc_status CHECK (status IN ('WAITING','RUNNING','SUCCEEDED','FAILED')),
  CONSTRAINT chk_device_parameter_recalc_range CHECK (from_minute < to_minute),
  CONSTRAINT fk_device_parameter_recalc_timeline
    FOREIGN KEY (timeline_revision_id) REFERENCES biz_device_parameter_timeline_revision (timeline_revision_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_recalc_building
    FOREIGN KEY (building_id) REFERENCES building (building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_recalc_equipment
    FOREIGN KEY (equip_id) REFERENCES biz_equipment (equip_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_legacy_staging (
  staging_id VARCHAR(32) PRIMARY KEY,
  migration_batch_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  legacy_field VARCHAR(50) NOT NULL,
  raw_value DECIMAL(30,12) NOT NULL,
  raw_unit_evidence VARCHAR(100),
  mapping_status VARCHAR(40) NOT NULL,
  definition_id VARCHAR(32),
  candidate_id VARCHAR(32),
  version_id VARCHAR(32),
  evidence_reference VARCHAR(255),
  captured_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  INDEX idx_device_parameter_legacy_status (mapping_status, building_id, equip_id),
  CONSTRAINT uk_device_parameter_legacy_staging UNIQUE (migration_batch_id, equip_id, legacy_field),
  CONSTRAINT chk_device_parameter_legacy_status CHECK (mapping_status IN ('PENDING_MAPPING','PENDING_PROFESSIONAL_REVIEW','READY_FOR_REVIEW','MIGRATED')),
  CONSTRAINT fk_device_parameter_legacy_staging_candidate
    FOREIGN KEY (candidate_id) REFERENCES biz_device_parameter_candidate (candidate_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_legacy_staging_version
    FOREIGN KEY (version_id) REFERENCES biz_device_parameter_version (version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_legacy_staging_building
    FOREIGN KEY (building_id) REFERENCES building (building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_legacy_staging_equipment
    FOREIGN KEY (equip_id) REFERENCES biz_equipment (equip_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_legacy_staging_definition
    FOREIGN KEY (definition_id) REFERENCES biz_device_parameter_definition (definition_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_legacy_mapping (
  legacy_mapping_id VARCHAR(32) PRIMARY KEY,
  equipment_type_code VARCHAR(20) NOT NULL,
  legacy_field VARCHAR(50) NOT NULL,
  mapping_mode VARCHAR(20) NOT NULL,
  definition_id VARCHAR(32),
  source_unit VARCHAR(20),
  scale_value DECIMAL(30,12),
  offset_value DECIMAL(30,12),
  evidence_reference VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL,
  config_revision INT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  CONSTRAINT uk_device_parameter_legacy_mapping UNIQUE (equipment_type_code, legacy_field),
  CONSTRAINT chk_device_parameter_legacy_field CHECK (legacy_field IN ('rated_capacity','rated_power','design_cop')),
  CONSTRAINT chk_device_parameter_legacy_mode CHECK (mapping_mode IN ('MAPPED','NOT_APPLICABLE')),
  CONSTRAINT chk_device_parameter_legacy_mapping_shape CHECK (
    (mapping_mode='MAPPED' AND definition_id IS NOT NULL AND source_unit IS NOT NULL
      AND scale_value IS NOT NULL AND offset_value IS NOT NULL)
    OR (mapping_mode='NOT_APPLICABLE' AND definition_id IS NULL)
  ),
  CONSTRAINT chk_device_parameter_legacy_mapping_status CHECK (status IN ('DRAFT','ENABLED','DISABLED')),
  CONSTRAINT fk_device_parameter_legacy_mapping_type
    FOREIGN KEY (equipment_type_code) REFERENCES biz_equipment_type (type_code) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_legacy_mapping_definition
    FOREIGN KEY (definition_id) REFERENCES biz_device_parameter_definition (definition_id) ON DELETE RESTRICT,
  CONSTRAINT fk_device_parameter_legacy_mapping_unit
    FOREIGN KEY (source_unit) REFERENCES biz_device_parameter_unit (unit_code) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS biz_device_parameter_audit_log (
  audit_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32),
  actor_type VARCHAR(20) NOT NULL,
  operator_id BIGINT,
  action_type VARCHAR(50) NOT NULL,
  object_type VARCHAR(50) NOT NULL,
  object_id VARCHAR(32) NOT NULL,
  version_id VARCHAR(32),
  before_summary VARCHAR(1000),
  after_summary VARCHAR(1000),
  result VARCHAR(20) NOT NULL,
  reason_code VARCHAR(64),
  idempotency_key VARCHAR(160),
  request_sha256 CHAR(64),
  operation_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  INDEX idx_device_parameter_audit_building_time (building_id, operation_time),
  INDEX idx_device_parameter_audit_object (object_type, object_id, operation_time),
  CONSTRAINT uk_device_parameter_audit_idempotency UNIQUE (idempotency_key),
  CONSTRAINT chk_device_parameter_audit_actor CHECK (actor_type IN ('USER','DEVICE','SYSTEM_MIGRATION','SYSTEM_RECALCULATION')),
  CONSTRAINT chk_device_parameter_audit_result CHECK (result IN ('SUCCESS','REJECTED'))
);
