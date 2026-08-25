DROP TABLE IF EXISTS biz_collection_config_audit_log;
DROP TABLE IF EXISTS biz_collection_review_request;
DROP TABLE IF EXISTS biz_collection_policy_version;
DROP TABLE IF EXISTS biz_collection_policy;
DROP TABLE IF EXISTS biz_pending_device;
DROP TABLE IF EXISTS biz_onboarding_audit_log;
DROP TABLE IF EXISTS biz_product_point_template;
DROP TABLE IF EXISTS biz_device_product;
DROP TABLE IF EXISTS sys_building_access_request;
DROP TABLE IF EXISTS biz_data_quality_recalc_job;
DROP TABLE IF EXISTS biz_data_quality_fill_task;
DROP TABLE IF EXISTS biz_point_typical_value_config;
DROP TABLE IF EXISTS gen_column;
DROP TABLE IF EXISTS gen_table;
DROP TABLE IF EXISTS sys_user_building;
DROP TABLE IF EXISTS sys_role_menu;
DROP TABLE IF EXISTS sys_menu;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS biz_indicator;
DROP TABLE IF EXISTS biz_point_alias;
DROP TABLE IF EXISTS biz_data_source;
DROP TABLE IF EXISTS biz_data_point;
DROP TABLE IF EXISTS biz_telemetry_receipt_failure;
DROP TABLE IF EXISTS biz_telemetry_receipt;
DROP TABLE IF EXISTS biz_mqtt_failure_aggregate;
DROP TABLE IF EXISTS biz_device_identity;
DROP TABLE IF EXISTS biz_equipment;
DROP TABLE IF EXISTS biz_point_naming_rule;
DROP TABLE IF EXISTS biz_equipment_type;
DROP TABLE IF EXISTS biz_system_group;
DROP TABLE IF EXISTS biz_space;
DROP TABLE IF EXISTS building;

CREATE TABLE sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  nickname VARCHAR(50),
  phone VARCHAR(20),
  status TINYINT DEFAULT 1,
  del_flag TINYINT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_key VARCHAR(50) NOT NULL UNIQUE,
  role_name VARCHAR(50) NOT NULL,
  status TINYINT DEFAULT 1,
  data_scope VARCHAR(16) DEFAULT 'ALL',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, role_id)
);

CREATE TABLE sys_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id BIGINT DEFAULT 0,
  menu_name VARCHAR(50) NOT NULL,
  menu_type CHAR(1) DEFAULT 'M',
  path VARCHAR(200),
  component VARCHAR(255),
  perms VARCHAR(100),
  icon VARCHAR(100),
  visible TINYINT DEFAULT 1,
  status TINYINT DEFAULT 1,
  sort_order INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_role_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  UNIQUE (role_id, menu_id)
);

CREATE TABLE building (
  building_id VARCHAR(32) PRIMARY KEY,
  building_name VARCHAR(100) NOT NULL,
  building_code VARCHAR(50),
  building_type VARCHAR(30),
  construction_year INT,
  total_gfa DECIMAL(14,2),
  above_ground_gfa DECIMAL(14,2),
  underground_gfa DECIMAL(14,2),
  climate_zone VARCHAR(30),
  design_occupancy INT,
  operating_hours VARCHAR(100),
  occupancy_schedule VARCHAR(2000),
  bems_system VARCHAR(100),
  bems_protocol VARCHAR(50),
  region_code VARCHAR(12),
  latitude DECIMAL(10,7),
  longitude DECIMAL(10,7),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  del_flag TINYINT DEFAULT 0
);

CREATE TABLE biz_space (
  space_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  parent_space_id VARCHAR(32),
  space_name VARCHAR(100) NOT NULL,
  space_code VARCHAR(50),
  space_type VARCHAR(50),
  floor_level INT,
  usable_area DECIMAL(12,2),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(32),
  update_by VARCHAR(32),
  del_flag TINYINT DEFAULT 0,
  UNIQUE (building_id, space_code),
  UNIQUE (space_id, building_id)
);

CREATE TABLE biz_system_group (
  system_group_id VARCHAR(32) PRIMARY KEY,
  system_group_code VARCHAR(50) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  system_type VARCHAR(100),
  system_group_name VARCHAR(100),
  group_desc VARCHAR(500),
  design_cop DECIMAL(8,2),
  design_capacity DECIMAL(12,2),
  annual_budget DECIMAL(14,2),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  del_flag TINYINT DEFAULT 0,
  UNIQUE (building_id, system_group_code),
  UNIQUE (system_group_id, building_id)
);

CREATE TABLE biz_equipment_type (
  type_code VARCHAR(20) PRIMARY KEY,
  type_name VARCHAR(100) NOT NULL,
  asset_code_prefix VARCHAR(20) NOT NULL,
  equip_category VARCHAR(50) NOT NULL,
  standard_source VARCHAR(32) NOT NULL,
  status TINYINT DEFAULT 1
);

CREATE TABLE biz_point_naming_rule (
  rule_id VARCHAR(32) PRIMARY KEY,
  standard_version VARCHAR(32) NOT NULL,
  family_code VARCHAR(20) NOT NULL,
  component_code VARCHAR(20) NOT NULL,
  code_template VARCHAR(100) NOT NULL,
  standard_source VARCHAR(32) NOT NULL,
  status TINYINT DEFAULT 1,
  UNIQUE (standard_version, family_code, component_code)
);

CREATE TABLE biz_equipment (
  equip_id VARCHAR(32) PRIMARY KEY,
  equip_code VARCHAR(50) NOT NULL,
  equip_name VARCHAR(100),
  type_code VARCHAR(20) NOT NULL,
  equip_category VARCHAR(30),
  system_group_id VARCHAR(32),
  building_id VARCHAR(32) NOT NULL,
  space_id VARCHAR(32),
  product_id VARCHAR(32),
  manufacturer VARCHAR(100),
  rated_capacity DECIMAL(12,4),
  rated_power DECIMAL(12,4),
  design_cop DECIMAL(12,4),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  del_flag TINYINT DEFAULT 0,
  UNIQUE (building_id, equip_code),
  UNIQUE (equip_id, building_id)
);

CREATE INDEX idx_equipment_product ON biz_equipment (product_id);

CREATE TABLE biz_device_identity (
  identity_id VARCHAR(32) PRIMARY KEY,
  identity_type VARCHAR(20) NOT NULL,
  identity_value VARCHAR(100) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  expected_profile_code VARCHAR(50) NOT NULL,
  max_ack_mode VARCHAR(30) NOT NULL DEFAULT 'EVIDENCE_ONLY',
  correlation_policy VARCHAR(40) NOT NULL DEFAULT 'NONE',
  device_ack_topic VARCHAR(200),
  adapter_ack_topic VARCHAR(200),
  status TINYINT DEFAULT 1,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (identity_type, identity_value)
);

CREATE TABLE biz_telemetry_receipt (
  canonical_message_id VARCHAR(64) PRIMARY KEY,
  identity_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  profile_code VARCHAR(50) NOT NULL,
  source_message_id VARCHAR(128),
  source_seq BIGINT,
  collected_at TIMESTAMP(3),
  adapter_received_at TIMESTAMP(3) NOT NULL,
  first_platform_received_at TIMESTAMP(3) NOT NULL,
  last_platform_received_at TIMESTAMP(3) NOT NULL,
  persisted_at TIMESTAMP(3) NOT NULL,
  retransmitted_at TIMESTAMP(3),
  batch_id VARCHAR(128),
  id_source VARCHAR(30) NOT NULL,
  time_source VARCHAR(30) NOT NULL,
  dedup_mode VARCHAR(20) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  configured_ack_mode VARCHAR(30) NOT NULL,
  actual_ack_mode VARCHAR(30) NOT NULL,
  downgrade_reason VARCHAR(100),
  receipt_status VARCHAR(40) NOT NULL,
  result_code VARCHAR(60) NOT NULL,
  metric_count INT NOT NULL,
  attempt_count INT NOT NULL DEFAULT 1,
  device_puback_state VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
  adapter_publish_puback_state VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
  platform_consumer_ack_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  application_ack_puback_state VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE',
  application_ack_published_at TIMESTAMP(3)
);

CREATE INDEX idx_receipt_building_persisted
  ON biz_telemetry_receipt (building_id, persisted_at);
CREATE INDEX idx_receipt_equip_persisted
  ON biz_telemetry_receipt (equip_id, persisted_at);

CREATE TABLE biz_telemetry_receipt_failure (
  failure_id VARCHAR(32) PRIMARY KEY,
  canonical_message_id VARCHAR(64),
  building_id VARCHAR(32),
  failure_stage VARCHAR(40) NOT NULL,
  failure_code VARCHAR(60) NOT NULL,
  safe_detail VARCHAR(500),
  occurred_at TIMESTAMP(3) NOT NULL
);

CREATE TABLE biz_mqtt_failure_aggregate (
  aggregate_id VARCHAR(32) PRIMARY KEY,
  bucket_start TIMESTAMP(3) NOT NULL,
  component VARCHAR(30) NOT NULL,
  failure_category VARCHAR(60) NOT NULL,
  broker_endpoint VARCHAR(255) NOT NULL,
  occurrence_count BIGINT NOT NULL,
  first_occurred_at TIMESTAMP(3) NOT NULL,
  last_occurred_at TIMESTAMP(3) NOT NULL,
  UNIQUE (bucket_start, component, failure_category, broker_endpoint)
);

CREATE TABLE biz_device_product (
  product_id VARCHAR(32) PRIMARY KEY,
  product_code VARCHAR(50) NOT NULL UNIQUE,
  product_name VARCHAR(100) NOT NULL,
  manufacturer VARCHAR(100),
  model VARCHAR(100),
  equipment_type_code VARCHAR(20) NOT NULL,
  expected_profile_code VARCHAR(50) NOT NULL,
  identity_type VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT chk_device_product_status
    CHECK (status IN ('DRAFT', 'ENABLED', 'DISABLED')),
  CONSTRAINT fk_device_product_equipment_type
    FOREIGN KEY (equipment_type_code) REFERENCES biz_equipment_type (type_code)
);

CREATE INDEX idx_device_product_type_status
  ON biz_device_product (equipment_type_code, status);

CREATE TABLE biz_product_point_template (
  template_point_id VARCHAR(32) PRIMARY KEY,
  product_id VARCHAR(32) NOT NULL,
  metric_code VARCHAR(100) NOT NULL,
  point_name_template VARCHAR(100) NOT NULL,
  suffix_code VARCHAR(20),
  unit VARCHAR(20) NOT NULL,
  min_value DECIMAL(12,4),
  max_value DECIMAL(12,4),
  for_calc TINYINT NOT NULL DEFAULT 0,
  required_flag TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_product_point_template_metric UNIQUE (product_id, metric_code),
  CONSTRAINT fk_product_point_template_product
    FOREIGN KEY (product_id) REFERENCES biz_device_product (product_id)
);

CREATE INDEX idx_product_point_template_status
  ON biz_product_point_template (product_id, status, sort_order);

-- H2 使用 TEXT 模拟 MySQL 中已被服务层限界的规范化指标样例，不保存原始 MQTT 载荷。
CREATE TABLE biz_pending_device (
  pending_id VARCHAR(32) PRIMARY KEY,
  identity_type VARCHAR(20) NOT NULL,
  identity_value VARCHAR(100) NOT NULL,
  profile_code VARCHAR(50) NOT NULL,
  last_profile_version INT NOT NULL,
  first_seen_time TIMESTAMP(3) NOT NULL,
  last_seen_time TIMESTAMP(3) NOT NULL,
  report_count BIGINT NOT NULL DEFAULT 1,
  latest_event_time TIMESTAMP(3) NOT NULL,
  latest_time_source VARCHAR(20) NOT NULL,
  latest_metrics_json TEXT NOT NULL,
  sample_truncated TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'DISCOVERED',
  bound_identity_id VARCHAR(32),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_pending_device_identity UNIQUE (identity_type, identity_value),
  CONSTRAINT chk_pending_device_status
    CHECK (status IN ('DISCOVERED', 'BOUND', 'IGNORED')),
  CONSTRAINT chk_pending_device_time_source
    CHECK (latest_time_source IN ('DEVICE_REPORTED', 'SERVER_RECEIVED')),
  CONSTRAINT fk_pending_device_bound_identity
    FOREIGN KEY (bound_identity_id) REFERENCES biz_device_identity (identity_id)
);

CREATE INDEX idx_pending_device_expiry
  ON biz_pending_device (status, last_seen_time, pending_id);
CREATE INDEX idx_pending_device_bound_identity
  ON biz_pending_device (bound_identity_id);

CREATE TABLE biz_onboarding_audit_log (
  audit_id VARCHAR(32) PRIMARY KEY,
  operator_id BIGINT NOT NULL,
  action_type VARCHAR(50) NOT NULL,
  object_type VARCHAR(50) NOT NULL,
  object_id VARCHAR(32) NOT NULL,
  before_summary VARCHAR(1000),
  after_summary VARCHAR(1000),
  result VARCHAR(20) NOT NULL,
  operation_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX idx_onboarding_audit_object
  ON biz_onboarding_audit_log (object_type, object_id, operation_time);

CREATE TABLE biz_data_point (
  point_id VARCHAR(32) PRIMARY KEY,
  point_code VARCHAR(100) NOT NULL,
  point_name VARCHAR(100),
  building_id VARCHAR(32) NOT NULL,
  system_group_id VARCHAR(32),
  equip_id VARCHAR(32),
  naming_rule_id VARCHAR(32) NOT NULL,
  family_code VARCHAR(20) NOT NULL,
  component_code VARCHAR(20) NOT NULL,
  suffix_code VARCHAR(20),
  data_type VARCHAR(20),
  unit VARCHAR(20),
  is_for_calc TINYINT DEFAULT 0,
  default_value DECIMAL(12,4),
  value_max DECIMAL(12,4),
  value_min DECIMAL(12,4),
  status VARCHAR(20) DEFAULT 'ONLINE',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  del_flag TINYINT DEFAULT 0,
  UNIQUE (building_id, point_code),
  UNIQUE (point_id, building_id)
);

-- H2 镜像保留治理表的关系、状态与保留期约束；运行状态仍只在单体内存中保存。
CREATE TABLE biz_data_source (
  source_id VARCHAR(32) PRIMARY KEY,
  source_code VARCHAR(50) NOT NULL,
  source_name VARCHAR(100) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  source_category VARCHAR(32) NOT NULL,
  transport_type VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  description VARCHAR(500),
  config_revision INT NOT NULL DEFAULT 0,
  runtime_revision BIGINT NOT NULL DEFAULT 0,
  create_by BIGINT,
  update_by BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_data_source_code UNIQUE (source_code),
  CONSTRAINT uk_data_source_id_building UNIQUE (source_id, building_id),
  CONSTRAINT chk_data_source_category CHECK (source_category = 'DEVICE_ACCESS'),
  CONSTRAINT chk_data_source_transport CHECK (transport_type IN ('MQTT', 'HTTP')),
  CONSTRAINT chk_data_source_status CHECK (status IN ('DRAFT', 'ENABLED', 'DISABLED')),
  CONSTRAINT chk_data_source_config_revision CHECK (config_revision >= 0),
  CONSTRAINT chk_data_source_runtime_revision CHECK (runtime_revision >= 0),
  CONSTRAINT fk_data_source_building
    FOREIGN KEY (building_id) REFERENCES building (building_id) ON DELETE RESTRICT
);

CREATE INDEX idx_data_source_building_status
  ON biz_data_source (building_id, status);

CREATE TABLE biz_point_alias (
  alias_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  source_id VARCHAR(32),
  source_system VARCHAR(50) NOT NULL,
  source_point_code VARCHAR(255) NOT NULL,
  point_id VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  revision INT NOT NULL DEFAULT 0,
  create_by BIGINT,
  update_by BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_alias_source UNIQUE (building_id, source_system, source_point_code),
  CONSTRAINT uk_alias_id_building UNIQUE (alias_id, building_id),
  CONSTRAINT chk_collection_alias_status CHECK (status IN (0, 1, 2)),
  CONSTRAINT fk_alias_point_building
    FOREIGN KEY (point_id, building_id)
    REFERENCES biz_data_point (point_id, building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_alias_source_building
    FOREIGN KEY (source_id, building_id)
    REFERENCES biz_data_source (source_id, building_id) ON DELETE RESTRICT
);

CREATE INDEX idx_alias_source_building
  ON biz_point_alias (source_id, building_id);

CREATE TABLE biz_collection_policy (
  policy_id VARCHAR(32) PRIMARY KEY,
  source_id VARCHAR(32) NOT NULL,
  alias_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  active_version_id VARCHAR(32),
  draft_version_id VARCHAR(32),
  create_by BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_collection_policy_alias UNIQUE (alias_id),
  CONSTRAINT fk_collection_policy_source_building
    FOREIGN KEY (source_id, building_id)
    REFERENCES biz_data_source (source_id, building_id) ON DELETE RESTRICT,
  CONSTRAINT fk_collection_policy_alias_building
    FOREIGN KEY (alias_id, building_id)
    REFERENCES biz_point_alias (alias_id, building_id) ON DELETE RESTRICT
);

CREATE INDEX idx_collection_policy_source_building
  ON biz_collection_policy (source_id, building_id);
CREATE INDEX idx_collection_policy_active_version
  ON biz_collection_policy (active_version_id);
CREATE INDEX idx_collection_policy_draft_version
  ON biz_collection_policy (draft_version_id);

CREATE TABLE biz_collection_policy_version (
  version_id VARCHAR(32) PRIMARY KEY,
  policy_id VARCHAR(32) NOT NULL,
  version_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  enabled_flag TINYINT NOT NULL,
  expected_interval_seconds INT NOT NULL,
  allowed_delay_seconds INT NOT NULL,
  time_semantics VARCHAR(32) NOT NULL,
  raw_retention_mode VARCHAR(20) NOT NULL,
  raw_retention_days INT,
  minute_retention_mode VARCHAR(20) NOT NULL,
  minute_retention_days INT,
  source_code_snapshot VARCHAR(50) NOT NULL,
  source_point_code_snapshot VARCHAR(255) NOT NULL,
  point_id_snapshot VARCHAR(32) NOT NULL,
  point_code_snapshot VARCHAR(100) NOT NULL,
  data_type_snapshot VARCHAR(20),
  unit_snapshot VARCHAR(20),
  change_type VARCHAR(20) NOT NULL,
  change_source VARCHAR(32) NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  copied_from_version_id VARCHAR(32),
  revision INT NOT NULL DEFAULT 0,
  created_by BIGINT,
  published_by BIGINT,
  published_at TIMESTAMP(3),
  effective_from TIMESTAMP(3),
  effective_to TIMESTAMP(3),
  retired_at TIMESTAMP(3),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_collection_policy_version_no UNIQUE (policy_id, version_no),
  CONSTRAINT chk_collection_policy_version_no CHECK (version_no > 0),
  CONSTRAINT chk_collection_policy_version_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
  CONSTRAINT chk_collection_policy_enabled_flag CHECK (enabled_flag IN (0, 1)),
  CONSTRAINT chk_collection_policy_interval CHECK (expected_interval_seconds > 0),
  CONSTRAINT chk_collection_policy_allowed_delay CHECK (allowed_delay_seconds >= 0),
  CONSTRAINT chk_collection_policy_time_semantics CHECK (time_semantics = 'DEVICE_EVENT_TIME'),
  CONSTRAINT chk_collection_policy_raw_retention CHECK (
    (raw_retention_mode = 'FIXED_DAYS' AND raw_retention_days > 0)
    OR (raw_retention_mode = 'LONG_TERM' AND raw_retention_days IS NULL)
  ),
  CONSTRAINT chk_collection_policy_minute_retention CHECK (
    (minute_retention_mode = 'FIXED_DAYS' AND minute_retention_days > 0)
    OR (minute_retention_mode = 'LONG_TERM' AND minute_retention_days IS NULL)
  ),
  CONSTRAINT chk_collection_policy_change_type
    CHECK (change_type IN ('CREATE', 'UPDATE', 'DISABLE', 'ROLLBACK', 'INITIAL_MIGRATION')),
  CONSTRAINT chk_collection_policy_change_source
    CHECK (change_source IN ('MANUAL', 'INITIAL_MIGRATION')),
  CONSTRAINT chk_collection_policy_change_reason CHECK (CHAR_LENGTH(TRIM(change_reason)) > 0),
  CONSTRAINT chk_collection_policy_revision CHECK (revision >= 0),
  CONSTRAINT fk_collection_policy_version_policy
    FOREIGN KEY (policy_id) REFERENCES biz_collection_policy (policy_id) ON DELETE RESTRICT
);

CREATE INDEX idx_collection_policy_version_status
  ON biz_collection_policy_version (policy_id, status, version_no);
CREATE INDEX idx_collection_policy_version_copied_from
  ON biz_collection_policy_version (copied_from_version_id);

CREATE TABLE biz_collection_review_request (
  request_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id VARCHAR(32) NOT NULL,
  target_config_revision INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  submitted_by BIGINT NOT NULL,
  submitted_at TIMESTAMP(3) NOT NULL,
  reviewer_id BIGINT,
  review_comment VARCHAR(500),
  reviewed_at TIMESTAMP(3),
  withdrawn_at TIMESTAMP(3),
  pending_marker TINYINT GENERATED ALWAYS AS
    (CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_collection_review_pending_target
    UNIQUE (target_type, target_id, pending_marker),
  CONSTRAINT chk_collection_review_target_type
    CHECK (target_type IN ('SOURCE_ACTIVATION', 'ALIAS_ACTIVATION', 'POLICY_VERSION')),
  CONSTRAINT chk_collection_review_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
  CONSTRAINT chk_collection_review_revision CHECK (target_config_revision >= 0),
  CONSTRAINT fk_collection_review_building
    FOREIGN KEY (building_id) REFERENCES building (building_id) ON DELETE RESTRICT
);

CREATE INDEX idx_collection_review_building_status
  ON biz_collection_review_request (building_id, status, submitted_at);
CREATE INDEX idx_collection_review_submitter
  ON biz_collection_review_request (submitted_by, status, submitted_at);

CREATE TABLE biz_collection_config_audit_log (
  audit_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  actor_type VARCHAR(20) NOT NULL,
  operator_id BIGINT,
  action_type VARCHAR(50) NOT NULL,
  object_type VARCHAR(50) NOT NULL,
  object_id VARCHAR(32) NOT NULL,
  version_id VARCHAR(32),
  before_summary VARCHAR(1000),
  after_summary VARCHAR(1000),
  result VARCHAR(20) NOT NULL,
  operation_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT chk_collection_audit_actor_type CHECK (actor_type IN ('USER', 'SYSTEM_MIGRATION')),
  CONSTRAINT chk_collection_audit_result CHECK (result = 'SUCCESS')
);

CREATE INDEX idx_collection_audit_building_time
  ON biz_collection_config_audit_log (building_id, operation_time);
CREATE INDEX idx_collection_audit_object
  ON biz_collection_config_audit_log (object_type, object_id, operation_time);
CREATE INDEX idx_collection_audit_version
  ON biz_collection_config_audit_log (version_id, operation_time);
CREATE INDEX idx_collection_audit_operator
  ON biz_collection_config_audit_log (operator_id, operation_time);

CREATE TABLE biz_indicator (
  indicator_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  indicator_code VARCHAR(100) NOT NULL,
  scope_type VARCHAR(20) NOT NULL,
  scope_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32),
  system_group_id VARCHAR(32),
  status TINYINT DEFAULT 1,
  UNIQUE (building_id, indicator_code, scope_type, scope_id)
);

CREATE TABLE sys_user_building (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, building_id)
);

CREATE TABLE sys_building_access_request (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  reviewer_id BIGINT,
  review_comment VARCHAR(500),
  review_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE gen_table (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_name VARCHAR(128) NOT NULL UNIQUE,
  table_comment VARCHAR(255),
  module_name VARCHAR(64) NOT NULL,
  business_name VARCHAR(64) NOT NULL,
  class_name VARCHAR(128) NOT NULL,
  package_name VARCHAR(255) NOT NULL,
  id_type VARCHAR(32) NOT NULL DEFAULT 'INPUT',
  logic_delete_column VARCHAR(128),
  scope_type VARCHAR(32) NOT NULL DEFAULT 'NONE',
  scope_column VARCHAR(128),
  read_roles VARCHAR(1000) NOT NULL,
  write_roles VARCHAR(1000) NOT NULL,
  generate_mode VARCHAR(32) NOT NULL DEFAULT 'JAVA_ZIP',
  status TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE gen_column (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_id BIGINT NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  column_comment VARCHAR(255),
  jdbc_type VARCHAR(64) NOT NULL,
  java_type VARCHAR(128) NOT NULL,
  java_field VARCHAR(128) NOT NULL,
  is_primary_key TINYINT NOT NULL DEFAULT 0,
  is_nullable TINYINT NOT NULL DEFAULT 1,
  is_logic_delete TINYINT NOT NULL DEFAULT 0,
  is_list TINYINT NOT NULL DEFAULT 1,
  is_query TINYINT NOT NULL DEFAULT 0,
  query_type VARCHAR(32) NOT NULL DEFAULT 'EQ',
  is_edit TINYINT NOT NULL DEFAULT 1,
  is_required TINYINT NOT NULL DEFAULT 0,
  component_type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  sort_order INT NOT NULL DEFAULT 0,
  UNIQUE (table_id, column_name)
);

-- 测试环境使用 H2 验证 MySQL 业务表；JSON 字段改为 TEXT，避免依赖真实 MySQL 方言。
CREATE TABLE biz_point_typical_value_config (
  config_id VARCHAR(32) PRIMARY KEY,
  point_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  typical_value DECIMAL(12,4) NOT NULL,
  unit VARCHAR(20) NOT NULL,
  source_description VARCHAR(500) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  valid_from TIMESTAMP(3) NOT NULL,
  valid_to TIMESTAMP(3),
  status VARCHAR(20) NOT NULL,
  version INT NOT NULL,
  created_by BIGINT NOT NULL,
  submitted_at TIMESTAMP(3),
  reviewer_id BIGINT,
  review_comment VARCHAR(500),
  reviewed_at TIMESTAMP(3),
  disabled_by BIGINT,
  disabled_reason VARCHAR(500),
  disabled_at TIMESTAMP(3),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_typical_point_version UNIQUE (point_id, version)
);

CREATE INDEX idx_typical_building_status
  ON biz_point_typical_value_config (building_id, status);
CREATE INDEX idx_typical_effective
  ON biz_point_typical_value_config (point_id, status, valid_from, valid_to);

CREATE TABLE biz_data_quality_fill_task (
  task_id VARCHAR(32) PRIMARY KEY,
  idempotency_key VARCHAR(160) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  point_id VARCHAR(32) NOT NULL,
  start_minute TIMESTAMP(3) NOT NULL,
  end_minute TIMESTAMP(3) NOT NULL,
  minute_count INT DEFAULT 0 NOT NULL,
  data_quality TINYINT NOT NULL,
  source_type VARCHAR(30) NOT NULL,
  algorithm_version VARCHAR(32) NOT NULL,
  evidence_json TEXT NOT NULL,
  typical_config_id VARCHAR(32),
  typical_config_version INT,
  apply_status VARCHAR(20) DEFAULT 'WAITING' NOT NULL,
  applied_count INT DEFAULT 0 NOT NULL,
  failed_count INT DEFAULT 0 NOT NULL,
  replaced_count INT DEFAULT 0 NOT NULL,
  voided_count INT DEFAULT 0 NOT NULL,
  failed_minutes_json TEXT,
  retry_count INT DEFAULT 0 NOT NULL,
  last_error VARCHAR(1000),
  generated_at TIMESTAMP(3) NOT NULL,
  closed_at TIMESTAMP(3),
  void_by BIGINT,
  void_reason VARCHAR(500),
  void_at TIMESTAMP(3),
  supersedes_task_id VARCHAR(32),
  recalc_job_id VARCHAR(32),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT chk_fill_quality CHECK (data_quality IN (1, 2)),
  CONSTRAINT uk_fill_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_fill_building_range
  ON biz_data_quality_fill_task (building_id, start_minute, end_minute);
CREATE INDEX idx_fill_point_range
  ON biz_data_quality_fill_task (point_id, start_minute, end_minute);
CREATE INDEX idx_fill_status_update
  ON biz_data_quality_fill_task (apply_status, update_time);
CREATE INDEX idx_fill_recalc_job
  ON biz_data_quality_fill_task (recalc_job_id, task_id);

-- H2 使用 TEXT 模拟 MySQL JSON；普通测试只验证管理数据，不连接真实 MySQL。
CREATE TABLE biz_data_quality_recalc_job (
  job_id VARCHAR(32) PRIMARY KEY,
  idempotency_key VARCHAR(160) NOT NULL,
  job_type VARCHAR(30) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  point_ids_json TEXT NOT NULL,
  from_minute TIMESTAMP(3) NOT NULL,
  to_minute TIMESTAMP(3) NOT NULL,
  supersedes_task_id VARCHAR(32),
  reason VARCHAR(500) NOT NULL,
  operator_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  phase VARCHAR(20) NOT NULL,
  cursor_minute TIMESTAMP(3) NOT NULL,
  void_target_minutes_json TEXT,
  q0_count INT DEFAULT 0 NOT NULL,
  q1_count INT DEFAULT 0 NOT NULL,
  q2_count INT DEFAULT 0 NOT NULL,
  missing_count INT DEFAULT 0 NOT NULL,
  voided_count INT DEFAULT 0 NOT NULL,
  replaced_count INT DEFAULT 0 NOT NULL,
  last_error VARCHAR(1000),
  started_at TIMESTAMP(3),
  finished_at TIMESTAMP(3),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_recalc_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_recalc_status_cursor
  ON biz_data_quality_recalc_job (status, update_time, job_id);
CREATE INDEX idx_recalc_building_range
  ON biz_data_quality_recalc_job
  (building_id, status, from_minute, to_minute);
CREATE INDEX idx_recalc_supersedes
  ON biz_data_quality_recalc_job (supersedes_task_id, create_time);

-- Q0/Q1/Q2 使用策略治理的 H2 隔离镜像。真实 MySQL 增量迁移保留在 18 号脚本；
-- 此处仅提供管理状态、审核证据和恢复任务的结构，普通测试不会连接 TDengine。
-- schema-test.sql 会在同一 JVM 的多个 Spring 上下文中重复初始化。先按依赖反向
-- 清理治理表，避免残留的治理外键干扰继承业务表的既有清理顺序。
-- 因此 H2 不持有指向既有 building、biz_data_point 的外键；应用服务会校验范围，
-- 正式 MySQL 迁移仍以 RESTRICT 外键强制该完整性边界。
DROP TABLE IF EXISTS biz_quality_usage_recovery_task;
DROP TABLE IF EXISTS biz_quality_usage_policy_level;
DROP TABLE IF EXISTS biz_quality_usage_review_request;
DROP TABLE IF EXISTS biz_quality_usage_policy_version;
DROP TABLE IF EXISTS biz_quality_usage_policy;
DROP TABLE IF EXISTS biz_quality_usage_change_set;
DROP TABLE IF EXISTS biz_quality_usage_audit_log;
DROP TABLE IF EXISTS biz_quality_usage_scenario;
DROP TABLE IF EXISTS biz_quality_usage_config_revision;

CREATE TABLE biz_quality_usage_scenario (
  scenario_id VARCHAR(32) PRIMARY KEY,
  scenario_code VARCHAR(64) NOT NULL,
  scenario_name VARCHAR(100) NOT NULL,
  adapter_type VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  introduced_version VARCHAR(32) NOT NULL,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  enabled_at TIMESTAMP(3),
  disabled_at TIMESTAMP(3),
  status_reason VARCHAR(500),
  CONSTRAINT uk_quality_usage_scenario_code UNIQUE (scenario_code),
  CONSTRAINT chk_quality_usage_scenario_status CHECK (status IN ('DRAFT','ENABLED','DISABLED'))
);

CREATE TABLE biz_quality_usage_change_set (
  change_set_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  status VARCHAR(20) NOT NULL,
  revision INT NOT NULL DEFAULT 0,
  submitted_revision INT,
  created_by BIGINT NOT NULL,
  has_been_submitted TINYINT NOT NULL DEFAULT 0,
  title VARCHAR(100) NOT NULL,
  description VARCHAR(1000),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  submitted_at TIMESTAMP(3),
  published_at TIMESTAMP(3),
  cancelled_at TIMESTAMP(3),
  last_failure_code VARCHAR(64),
  CONSTRAINT chk_quality_usage_change_set_status CHECK (status IN ('DRAFT','PENDING','PUBLISHED','CANCELLED')),
  CONSTRAINT chk_quality_usage_change_set_revision CHECK (revision >= 0),
  CONSTRAINT chk_quality_usage_change_set_submitted CHECK (has_been_submitted IN (0,1))
);

CREATE INDEX idx_quality_usage_change_set_building
  ON biz_quality_usage_change_set (building_id, status, create_time);

CREATE TABLE biz_quality_usage_policy (
  policy_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  point_id VARCHAR(32) NOT NULL,
  scenario_id VARCHAR(32) NOT NULL,
  current_active_version_id VARCHAR(32),
  pending_review_request_id VARCHAR(32),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_quality_usage_policy_identity UNIQUE (point_id, scenario_id),
  CONSTRAINT fk_quality_usage_policy_scenario
    FOREIGN KEY (scenario_id) REFERENCES biz_quality_usage_scenario (scenario_id) ON DELETE RESTRICT
);

CREATE INDEX idx_quality_usage_policy_building
  ON biz_quality_usage_policy (building_id, point_id);

CREATE TABLE biz_quality_usage_policy_version (
  version_id VARCHAR(32) PRIMARY KEY,
  policy_id VARCHAR(32) NOT NULL,
  change_set_id VARCHAR(32),
  version_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  base_active_version_id VARCHAR(32),
  copied_from_version_id VARCHAR(32),
  effective_from_ms BIGINT,
  effective_to_ms BIGINT,
  initial_baseline TINYINT NOT NULL DEFAULT 0,
  published_config_revision BIGINT,
  change_source VARCHAR(40) NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  created_by BIGINT,
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  published_by BIGINT,
  published_at TIMESTAMP(3),
  retired_at TIMESTAMP(3),
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_quality_usage_version_no UNIQUE (policy_id, version_no),
  CONSTRAINT chk_quality_usage_version_no CHECK (version_no > 0),
  CONSTRAINT chk_quality_usage_version_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT chk_quality_usage_initial_baseline CHECK (initial_baseline IN (0,1)),
  CONSTRAINT chk_quality_usage_effective_range CHECK (
    effective_to_ms IS NULL OR effective_from_ms IS NULL OR effective_to_ms >= effective_from_ms
  ),
  CONSTRAINT chk_quality_usage_formal_effective_from CHECK (
    status = 'DRAFT' OR initial_baseline = 1
    OR (effective_from_ms IS NOT NULL AND MOD(effective_from_ms, 60000) = 0)
  ),
  CONSTRAINT chk_quality_usage_effective_to_alignment CHECK (
    effective_to_ms IS NULL OR MOD(effective_to_ms, 60000) = 0
  ),
  CONSTRAINT chk_quality_usage_initial_baseline_shape CHECK (
    initial_baseline = 0
    OR (version_no = 1 AND change_set_id IS NULL AND effective_from_ms IS NULL)
  ),
  CONSTRAINT fk_quality_usage_version_policy
    FOREIGN KEY (policy_id) REFERENCES biz_quality_usage_policy (policy_id) ON DELETE RESTRICT,
  CONSTRAINT fk_quality_usage_version_change_set
    FOREIGN KEY (change_set_id) REFERENCES biz_quality_usage_change_set (change_set_id) ON DELETE RESTRICT
);

CREATE INDEX idx_quality_usage_version_policy_status
  ON biz_quality_usage_policy_version (policy_id, status, version_no);
CREATE INDEX idx_quality_usage_version_change_set
  ON biz_quality_usage_policy_version (change_set_id, status, version_no);

CREATE TABLE biz_quality_usage_policy_level (
  policy_level_id VARCHAR(32) PRIMARY KEY,
  version_id VARCHAR(32) NOT NULL,
  quality_level VARCHAR(2) NOT NULL,
  CONSTRAINT uk_quality_usage_policy_level UNIQUE (version_id, quality_level),
  CONSTRAINT chk_quality_usage_policy_level CHECK (quality_level IN ('Q0','Q1','Q2')),
  CONSTRAINT fk_quality_usage_policy_level_version
    FOREIGN KEY (version_id) REFERENCES biz_quality_usage_policy_version (version_id) ON DELETE RESTRICT
);

CREATE TABLE biz_quality_usage_review_request (
  request_id VARCHAR(32) PRIMARY KEY,
  change_set_id VARCHAR(32) NOT NULL,
  request_no INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  review_mode VARCHAR(20) NOT NULL,
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
  idempotency_key VARCHAR(160),
  request_sha256 CHAR(64),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_quality_usage_review_no UNIQUE (change_set_id, request_no),
  CONSTRAINT uk_quality_usage_review_idempotency UNIQUE (idempotency_key),
  CONSTRAINT chk_quality_usage_review_status CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT chk_quality_usage_review_mode CHECK (review_mode IN ('NORMAL','DIRECT_PUBLISH')),
  CONSTRAINT fk_quality_usage_review_change_set
    FOREIGN KEY (change_set_id) REFERENCES biz_quality_usage_change_set (change_set_id) ON DELETE RESTRICT
);

CREATE INDEX idx_quality_usage_review_change_set
  ON biz_quality_usage_review_request (change_set_id, status, submitted_at);

CREATE TABLE biz_quality_usage_audit_log (
  audit_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
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
  config_revision BIGINT,
  idempotency_key VARCHAR(160),
  request_sha256 CHAR(64),
  operation_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_quality_usage_audit_idempotency UNIQUE (idempotency_key),
  CONSTRAINT chk_quality_usage_audit_actor CHECK (actor_type IN ('USER','SYSTEM_MIGRATION')),
  CONSTRAINT chk_quality_usage_audit_result CHECK (result IN ('SUCCESS','REJECTED'))
);

CREATE INDEX idx_quality_usage_audit_building_time
  ON biz_quality_usage_audit_log (building_id, operation_time);
CREATE INDEX idx_quality_usage_audit_operator_time
  ON biz_quality_usage_audit_log (operator_id, operation_time);

CREATE TABLE biz_quality_usage_config_revision (
  singleton_id INT PRIMARY KEY,
  config_revision BIGINT NOT NULL,
  last_change_summary VARCHAR(500),
  updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT chk_quality_usage_revision_singleton CHECK (singleton_id = 1),
  CONSTRAINT chk_quality_usage_revision_nonnegative CHECK (config_revision >= 0)
);

INSERT INTO biz_quality_usage_config_revision
  (singleton_id, config_revision, last_change_summary)
VALUES (1, 0, 'H2_TEST_INITIAL');

-- TDengine 当前状态投影写入失败时的 MySQL 持久化幂等对账任务。
CREATE TABLE biz_quality_usage_recovery_task (
  task_id VARCHAR(32) PRIMARY KEY,
  task_type VARCHAR(40) NOT NULL,
  business_key VARCHAR(200) NOT NULL,
  payload_json TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1000),
  create_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uk_quality_usage_recovery_business_key UNIQUE (business_key),
  CONSTRAINT chk_quality_usage_recovery_status CHECK (status IN ('WAITING','RUNNING','DONE','FAILED')),
  CONSTRAINT chk_quality_usage_recovery_retry CHECK (retry_count >= 0)
);

CREATE INDEX idx_quality_usage_recovery_status
  ON biz_quality_usage_recovery_task (status, update_time, task_id);
