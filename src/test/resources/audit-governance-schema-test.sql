-- 审计治理公共基础的 H2 隔离镜像。普通测试不连接真实 MySQL 或生产账号。
DROP TABLE IF EXISTS sys_security_audit_event;
DROP TABLE IF EXISTS sys_audit_export_job;
DROP TABLE IF EXISTS sys_password_setup_token;
DROP TABLE IF EXISTS sys_sensitive_change_request;
DROP TABLE IF EXISTS sys_user_backend_duty;
DROP TABLE IF EXISTS sys_backend_duty;

CREATE TABLE sys_backend_duty (
  duty_key VARCHAR(64) PRIMARY KEY,
  duty_name VARCHAR(100) NOT NULL,
  description VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL,
  risk_level VARCHAR(20) NOT NULL,
  version INT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user_backend_duty (
  assignment_id VARCHAR(32) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  duty_key VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  effective_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP,
  source_request_id VARCHAR(32),
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_by BIGINT,
  revoked_at TIMESTAMP,
  revoke_request_id VARCHAR(32),
  UNIQUE (user_id,duty_key),
  FOREIGN KEY (duty_key) REFERENCES sys_backend_duty(duty_key)
);

CREATE INDEX idx_user_backend_duty_active
  ON sys_user_backend_duty(user_id,status,effective_at,expires_at);

CREATE TABLE sys_sensitive_change_request (
  request_id VARCHAR(32) PRIMARY KEY,
  operation_code VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  building_id VARCHAR(32),
  target_type VARCHAR(64) NOT NULL,
  target_id VARCHAR(128) NOT NULL,
  command_json CLOB NOT NULL,
  request_sha256 CHAR(64) NOT NULL,
  impact_summary VARCHAR(1000) NOT NULL,
  submitted_by BIGINT NOT NULL,
  submitted_at TIMESTAMP,
  reviewer_id BIGINT,
  review_comment VARCHAR(500),
  reviewed_at TIMESTAMP,
  executed_at TIMESTAMP,
  execution_error_code VARCHAR(64),
  idempotency_key VARCHAR(100) NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  environment_mode VARCHAR(20) NOT NULL,
  self_approval_dev_mode BOOLEAN NOT NULL DEFAULT FALSE,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (submitted_by,idempotency_key)
);

CREATE INDEX idx_sensitive_change_status_time
  ON sys_sensitive_change_request(status,create_time,request_id);
CREATE INDEX idx_sensitive_change_target
  ON sys_sensitive_change_request(target_type,target_id,create_time,request_id);
CREATE INDEX idx_sensitive_change_building
  ON sys_sensitive_change_request(building_id,create_time,request_id);
CREATE INDEX idx_sensitive_change_trace
  ON sys_sensitive_change_request(trace_id);

CREATE TABLE sys_password_setup_token (
  token_id VARCHAR(32) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  purpose VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  source_request_id VARCHAR(32) NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  used_at TIMESTAMP,
  UNIQUE (source_request_id,purpose)
);

CREATE INDEX idx_password_setup_user_status
  ON sys_password_setup_token(user_id,status,expires_at);

CREATE TABLE sys_security_audit_event (
  audit_id VARCHAR(32) PRIMARY KEY,
  source_module VARCHAR(50) NOT NULL,
  building_id VARCHAR(32),
  actor_type VARCHAR(20) NOT NULL,
  operator_id BIGINT,
  action_type VARCHAR(64) NOT NULL,
  object_type VARCHAR(64) NOT NULL,
  object_id VARCHAR(128) NOT NULL,
  version_id VARCHAR(64),
  review_request_id VARCHAR(32),
  before_summary VARCHAR(1000),
  after_summary VARCHAR(1000),
  result VARCHAR(20) NOT NULL,
  reason_code VARCHAR(64),
  trace_id VARCHAR(64) NOT NULL,
  operation_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  environment_mode VARCHAR(20) NOT NULL,
  self_approval_dev_mode BOOLEAN NOT NULL DEFAULT FALSE,
  first_at TIMESTAMP,
  last_at TIMESTAMP,
  attempt_count INT NOT NULL DEFAULT 1
);

CREATE INDEX idx_security_audit_building_time
  ON sys_security_audit_event(building_id,operation_time,audit_id);
CREATE INDEX idx_security_audit_operator_time
  ON sys_security_audit_event(operator_id,operation_time,audit_id);
CREATE INDEX idx_security_audit_object_time
  ON sys_security_audit_event(object_type,object_id,operation_time,audit_id);
CREATE INDEX idx_security_audit_result_time
  ON sys_security_audit_event(result,operation_time,audit_id);
CREATE INDEX idx_security_audit_trace
  ON sys_security_audit_event(trace_id);
CREATE INDEX idx_security_audit_retention
  ON sys_security_audit_event(operation_time,audit_id);

ALTER TABLE biz_collection_config_audit_log ADD COLUMN review_request_id VARCHAR(32);
ALTER TABLE biz_collection_config_audit_log ADD COLUMN reason_code VARCHAR(64);
ALTER TABLE biz_collection_config_audit_log ADD COLUMN trace_id VARCHAR(64);
ALTER TABLE biz_collection_config_audit_log ADD COLUMN environment_mode VARCHAR(20);
ALTER TABLE biz_collection_config_audit_log ADD COLUMN self_approval_dev_mode BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE biz_quality_usage_audit_log ADD COLUMN review_request_id VARCHAR(32);
ALTER TABLE biz_quality_usage_audit_log ADD COLUMN trace_id VARCHAR(64);
ALTER TABLE biz_quality_usage_audit_log ADD COLUMN environment_mode VARCHAR(20);
ALTER TABLE biz_quality_usage_audit_log ADD COLUMN self_approval_dev_mode BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE biz_device_parameter_audit_log ADD COLUMN review_request_id VARCHAR(32);
ALTER TABLE biz_device_parameter_audit_log ADD COLUMN trace_id VARCHAR(64);
ALTER TABLE biz_device_parameter_audit_log ADD COLUMN environment_mode VARCHAR(20);
ALTER TABLE biz_device_parameter_audit_log ADD COLUMN self_approval_dev_mode BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE biz_relation_audit_log ADD COLUMN actor_type VARCHAR(20);
ALTER TABLE biz_relation_audit_log ADD COLUMN trace_id VARCHAR(64);
ALTER TABLE biz_relation_audit_log ADD COLUMN environment_mode VARCHAR(20);
ALTER TABLE biz_relation_audit_log ADD COLUMN self_approval_dev_mode BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE biz_onboarding_audit_log ADD COLUMN building_id VARCHAR(32);
ALTER TABLE biz_onboarding_audit_log ADD COLUMN actor_type VARCHAR(20);
ALTER TABLE biz_onboarding_audit_log ADD COLUMN version_id VARCHAR(32);
ALTER TABLE biz_onboarding_audit_log ADD COLUMN review_request_id VARCHAR(32);
ALTER TABLE biz_onboarding_audit_log ADD COLUMN reason_code VARCHAR(64);
ALTER TABLE biz_onboarding_audit_log ADD COLUMN trace_id VARCHAR(64);
ALTER TABLE biz_onboarding_audit_log ADD COLUMN environment_mode VARCHAR(20);
ALTER TABLE biz_onboarding_audit_log ADD COLUMN self_approval_dev_mode BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE sys_audit_export_job (
  export_id VARCHAR(32) PRIMARY KEY,
  requested_by BIGINT NOT NULL,
  purpose VARCHAR(500) NOT NULL,
  query_json CLOB NOT NULL,
  query_sha256 CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  row_count INT,
  file_path VARCHAR(1000),
  file_sha256 CHAR(64),
  expires_at TIMESTAMP NOT NULL,
  error_code VARCHAR(64),
  trace_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at TIMESTAMP,
  completed_at TIMESTAMP,
  downloaded_by BIGINT,
  downloaded_at TIMESTAMP
);
CREATE INDEX idx_audit_export_owner_time
  ON sys_audit_export_job(requested_by,created_at,export_id);
CREATE INDEX idx_audit_export_cleanup
  ON sys_audit_export_job(status,expires_at,export_id);

INSERT INTO sys_backend_duty
  (duty_key,duty_name,description,status,risk_level,version)
VALUES
  ('BACKOFFICE_CHANGE_SUBMITTER','后台敏感变更提交','提交系统级敏感变更申请','ENABLED','HIGH',1),
  ('BACKOFFICE_CHANGE_REVIEWER','后台敏感变更审核','批准或拒绝系统级敏感变更申请','ENABLED','CRITICAL',1),
  ('AUDIT_EVIDENCE_VIEWER','审计证据查看','查询审计证据','ENABLED','HIGH',1),
  ('AUDIT_EVIDENCE_EXPORTER','审计证据导出','创建脱敏导出','ENABLED','CRITICAL',1),
  ('AUDIT_RETENTION_MANAGER','审计保留管理','维护保留策略草稿','ENABLED','CRITICAL',1),
  ('AUDIT_EVIDENCE_HOLD_MANAGER','审计证据保全','设置与解除证据保全','ENABLED','CRITICAL',1);
