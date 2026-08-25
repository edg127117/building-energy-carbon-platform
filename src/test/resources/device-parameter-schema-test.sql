-- 标准设备参数治理的 H2 隔离镜像。普通测试不会连接真实 MySQL、TDengine 或厂家设备。
DROP TABLE IF EXISTS biz_device_parameter_audit_log;
DROP TABLE IF EXISTS biz_device_parameter_legacy_mapping;
DROP TABLE IF EXISTS biz_device_parameter_legacy_staging;
DROP TABLE IF EXISTS biz_device_parameter_recalc_job;
DROP TABLE IF EXISTS biz_device_parameter_import_row;
DROP TABLE IF EXISTS biz_device_parameter_import_batch;
DROP TABLE IF EXISTS biz_device_parameter_timeline_segment;
DROP TABLE IF EXISTS biz_device_parameter_timeline_revision;
DROP TABLE IF EXISTS biz_device_parameter_review_request;
DROP TABLE IF EXISTS biz_device_parameter_version_value;
DROP TABLE IF EXISTS biz_device_parameter_version;
DROP TABLE IF EXISTS biz_device_parameter_set;
DROP TABLE IF EXISTS biz_device_parameter_conflict_member;
DROP TABLE IF EXISTS biz_device_parameter_conflict;
DROP TABLE IF EXISTS biz_device_parameter_candidate;
DROP TABLE IF EXISTS biz_product_parameter_template_value;
DROP TABLE IF EXISTS biz_product_parameter_template_revision;
DROP TABLE IF EXISTS biz_product_parameter_template;
DROP TABLE IF EXISTS biz_device_parameter_mapping_version;
DROP TABLE IF EXISTS biz_device_parameter_mapping;
DROP TABLE IF EXISTS biz_device_parameter_applicability;
DROP TABLE IF EXISTS biz_device_parameter_definition;
DROP TABLE IF EXISTS biz_device_parameter_unit;

CREATE TABLE biz_device_parameter_unit (
  unit_code VARCHAR(20) PRIMARY KEY,
  quantity_kind VARCHAR(50) NOT NULL,
  unit_symbol VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  evidence_reference VARCHAR(255) NOT NULL,
  config_revision INT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE biz_device_parameter_definition (
  definition_id VARCHAR(32) PRIMARY KEY,
  parameter_code VARCHAR(50) NOT NULL UNIQUE,
  parameter_name VARCHAR(100) NOT NULL,
  business_definition VARCHAR(500) NOT NULL,
  quantity_kind VARCHAR(50) NOT NULL,
  value_type VARCHAR(20) NOT NULL,
  standard_unit VARCHAR(20) NOT NULL,
  storage_scale INT NOT NULL,
  display_scale INT NOT NULL,
  evidence_reference VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL,
  config_revision INT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE biz_device_parameter_applicability (
  applicability_id VARCHAR(32) PRIMARY KEY,
  equipment_type_code VARCHAR(20) NOT NULL,
  definition_id VARCHAR(32) NOT NULL,
  required_flag TINYINT NOT NULL,
  formula_readable TINYINT NOT NULL,
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
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  UNIQUE (equipment_type_code,definition_id)
);

CREATE TABLE biz_device_parameter_mapping (
  mapping_id VARCHAR(32) PRIMARY KEY,
  profile_code VARCHAR(50) NOT NULL,
  source_path VARCHAR(255) NOT NULL,
  current_active_version_id VARCHAR(32),
  config_revision INT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  UNIQUE (profile_code,source_path)
);

CREATE TABLE biz_device_parameter_mapping_version (
  mapping_version_id VARCHAR(32) PRIMARY KEY,
  mapping_id VARCHAR(32) NOT NULL,
  version_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  profile_version VARCHAR(50) NOT NULL,
  definition_id VARCHAR(32) NOT NULL,
  source_unit VARCHAR(20) NOT NULL,
  scale_value DECIMAL(30,12) NOT NULL,
  offset_value DECIMAL(30,12) NOT NULL,
  required_flag TINYINT NOT NULL,
  base_version_id VARCHAR(32),
  copied_from_version_id VARCHAR(32),
  change_reason VARCHAR(500) NOT NULL,
  evidence_reference VARCHAR(255) NOT NULL,
  created_by BIGINT NOT NULL,
  published_by BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  published_at TIMESTAMP(3),
  UNIQUE (mapping_id,version_no)
);

CREATE TABLE biz_product_parameter_template (
  template_id VARCHAR(32) PRIMARY KEY,
  product_id VARCHAR(32) NOT NULL UNIQUE,
  current_active_revision_id VARCHAR(32),
  config_revision INT NOT NULL DEFAULT 0,
  create_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE biz_product_parameter_template_revision (
  template_revision_id VARCHAR(32) PRIMARY KEY,
  template_id VARCHAR(32) NOT NULL,
  revision_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  evidence_reference VARCHAR(255) NOT NULL,
  created_by BIGINT NOT NULL,
  published_by BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  published_at TIMESTAMP(3),
  UNIQUE (template_id,revision_no)
);

CREATE TABLE biz_product_parameter_template_value (
  template_value_id VARCHAR(32) PRIMARY KEY,
  template_revision_id VARCHAR(32) NOT NULL,
  definition_id VARCHAR(32) NOT NULL,
  raw_value DECIMAL(30,12) NOT NULL,
  raw_unit VARCHAR(20) NOT NULL,
  normalized_value DECIMAL(30,12) NOT NULL,
  source_reference VARCHAR(255) NOT NULL,
  sort_order INT NOT NULL,
  UNIQUE (template_revision_id,definition_id)
);

CREATE TABLE biz_device_parameter_candidate (
  candidate_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  definition_id VARCHAR(32),
  source_type VARCHAR(20) NOT NULL,
  source_reference VARCHAR(255) NOT NULL,
  source_version VARCHAR(100) NOT NULL,
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
  warning_flag TINYINT NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  current_flag TINYINT NOT NULL,
  supersedes_candidate_id VARCHAR(32),
  first_seen_at TIMESTAMP(3) NOT NULL,
  last_seen_at TIMESTAMP(3) NOT NULL,
  created_by BIGINT,
  UNIQUE (equip_id,source_type,source_parameter_key,source_version,payload_hash)
);

CREATE TABLE biz_device_parameter_conflict (
  conflict_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  definition_id VARCHAR(32) NOT NULL,
  status VARCHAR(20) NOT NULL,
  config_revision INT NOT NULL,
  selected_candidate_id VARCHAR(32),
  resolution_reason VARCHAR(500),
  resolved_by BIGINT,
  resolved_at TIMESTAMP(3),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  obsolete_at TIMESTAMP(3)
);

CREATE TABLE biz_device_parameter_conflict_member (
  conflict_id VARCHAR(32) NOT NULL,
  candidate_id VARCHAR(32) NOT NULL,
  PRIMARY KEY (conflict_id,candidate_id)
);

CREATE TABLE biz_device_parameter_set (
  parameter_set_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32) NOT NULL UNIQUE,
  current_timeline_revision_id VARCHAR(32),
  config_revision INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE biz_device_parameter_version (
  version_id VARCHAR(32) PRIMARY KEY,
  parameter_set_id VARCHAR(32) NOT NULL,
  version_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  open_marker TINYINT,
  config_revision INT NOT NULL,
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
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  published_at TIMESTAMP(3),
  UNIQUE (parameter_set_id,version_no),
  UNIQUE (parameter_set_id,open_marker)
);

CREATE TABLE biz_device_parameter_version_value (
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
  UNIQUE (version_id,definition_id)
);

CREATE TABLE biz_device_parameter_review_request (
  request_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  version_id VARCHAR(32) NOT NULL,
  request_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  submitted_revision INT NOT NULL,
  snapshot_json TEXT NOT NULL,
  snapshot_sha256 CHAR(64) NOT NULL,
  submitted_by BIGINT NOT NULL,
  submitted_at TIMESTAMP(3) NOT NULL,
  reviewer_id BIGINT,
  review_comment VARCHAR(500),
  reviewed_at TIMESTAMP(3),
  withdrawn_by BIGINT,
  withdrawn_at TIMESTAMP(3),
  idempotency_key VARCHAR(160) UNIQUE,
  request_sha256 CHAR(64),
  decision_idempotency_key VARCHAR(160) UNIQUE,
  decision_request_sha256 CHAR(64),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  UNIQUE (version_id,request_no)
);

CREATE TABLE biz_device_parameter_timeline_revision (
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
  impact_snapshot_json TEXT NOT NULL,
  UNIQUE (parameter_set_id,revision_no)
);

CREATE TABLE biz_device_parameter_timeline_segment (
  timeline_segment_id VARCHAR(32) PRIMARY KEY,
  timeline_revision_id VARCHAR(32) NOT NULL,
  business_effective_from TIMESTAMP(3) NOT NULL,
  business_effective_to TIMESTAMP(3),
  version_id VARCHAR(32) NOT NULL,
  UNIQUE (timeline_revision_id,business_effective_from)
);

CREATE TABLE biz_device_parameter_import_batch (
  import_batch_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  safe_file_name VARCHAR(255) NOT NULL,
  file_sha256 CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  row_count INT NOT NULL,
  valid_row_count INT NOT NULL,
  error_count INT NOT NULL,
  error_summary VARCHAR(1000),
  operator_id BIGINT NOT NULL,
  confirmed_by BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  confirmed_at TIMESTAMP(3),
  UNIQUE (building_id,file_sha256)
);

CREATE TABLE biz_device_parameter_import_row (
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
  UNIQUE (import_batch_id,row_no)
);

CREATE TABLE biz_device_parameter_recalc_job (
  job_id VARCHAR(32) PRIMARY KEY,
  idempotency_key VARCHAR(160) NOT NULL UNIQUE,
  timeline_revision_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  indicator_ids_json TEXT NOT NULL,
  from_minute TIMESTAMP(3) NOT NULL,
  to_minute TIMESTAMP(3) NOT NULL,
  status VARCHAR(20) NOT NULL,
  cursor_minute TIMESTAMP(3) NOT NULL,
  retry_count INT NOT NULL,
  last_error VARCHAR(1000),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  finished_at TIMESTAMP(3)
);

CREATE TABLE biz_device_parameter_legacy_staging (
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
  captured_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  UNIQUE (migration_batch_id,equip_id,legacy_field)
);

CREATE TABLE biz_device_parameter_legacy_mapping (
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
  config_revision INT NOT NULL,
  create_by BIGINT NOT NULL,
  update_by BIGINT NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  UNIQUE (equipment_type_code,legacy_field)
);

CREATE TABLE biz_device_parameter_audit_log (
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
  idempotency_key VARCHAR(160) UNIQUE,
  request_sha256 CHAR(64),
  operation_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL
);
