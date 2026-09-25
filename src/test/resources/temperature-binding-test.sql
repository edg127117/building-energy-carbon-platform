DROP TABLE IF EXISTS biz_temperature_job_item;
DROP TABLE IF EXISTS biz_temperature_job;
DROP TABLE IF EXISTS biz_temperature_binding;
DROP TABLE IF EXISTS biz_temperature_rule;
-- 温度模板规则与补齐进度只保存正式配置引用，不保存厂家凭据或伪造历史。


CREATE TABLE IF NOT EXISTS biz_temperature_rule (
  rule_id VARCHAR(32) PRIMARY KEY,
  adapter_id VARCHAR(50) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  source_scope VARCHAR(200) NOT NULL,
  model VARCHAR(100) NOT NULL,
  template_product_id VARCHAR(32) NOT NULL,
  numeric_source_id VARCHAR(32) NOT NULL,
  revision INT NOT NULL,
  enabled TINYINT NOT NULL,
  request_id VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_temperature_rule_scope(adapter_id,building_id,source_scope,model)
);
CREATE TABLE IF NOT EXISTS biz_temperature_binding (
  identity_id VARCHAR(32) NOT NULL,
  metric_code VARCHAR(100) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  equipment_id VARCHAR(32) NOT NULL,
  point_id VARCHAR(32) NOT NULL,
  alias_id VARCHAR(32) NOT NULL,
  template_product_id VARCHAR(32) NOT NULL,
  snapshot_json LONGTEXT NOT NULL,
  request_id VARCHAR(32) NOT NULL,
  effective_at_ms BIGINT NOT NULL,
  PRIMARY KEY(identity_id,metric_code)
);
CREATE TABLE IF NOT EXISTS biz_temperature_job (
  job_id VARCHAR(32) PRIMARY KEY,
  submitted_by BIGINT NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  created_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_temperature_job_idempotency(submitted_by,idempotency_key)
);
CREATE TABLE IF NOT EXISTS biz_temperature_job_item (
  job_id VARCHAR(32) NOT NULL,
  pending_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  command_json LONGTEXT NOT NULL,
  request_id VARCHAR(32),
  submission_status VARCHAR(32) NOT NULL,
  error_message VARCHAR(200),
  PRIMARY KEY(job_id,pending_id)
);
