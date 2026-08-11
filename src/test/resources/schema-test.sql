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
DROP TABLE IF EXISTS biz_data_point;
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

CREATE TABLE biz_device_identity (
  identity_id VARCHAR(32) PRIMARY KEY,
  identity_type VARCHAR(20) NOT NULL,
  identity_value VARCHAR(100) NOT NULL,
  equip_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  expected_profile_code VARCHAR(50) NOT NULL,
  status TINYINT DEFAULT 1,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (identity_type, identity_value)
);

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

CREATE TABLE biz_point_alias (
  alias_id VARCHAR(32) PRIMARY KEY,
  building_id VARCHAR(32) NOT NULL,
  source_system VARCHAR(50) NOT NULL,
  source_point_code VARCHAR(255) NOT NULL,
  point_id VARCHAR(32) NOT NULL,
  status TINYINT DEFAULT 1,
  UNIQUE (building_id, source_system, source_point_code)
);

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
