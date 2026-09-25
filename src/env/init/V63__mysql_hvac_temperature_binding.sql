-- 温度模板规则与补齐进度只保存正式配置引用，不保存厂家凭据或伪造历史。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;
CREATE TABLE IF NOT EXISTS biz_temperature_rule (
  rule_id VARCHAR(32) COLLATE utf8mb4_bin PRIMARY KEY,
  adapter_id VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
  building_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  source_scope VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
  model VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  template_product_id VARCHAR(32) NOT NULL,
  numeric_source_id VARCHAR(32) NOT NULL,
  revision INT NOT NULL,
  enabled TINYINT NOT NULL,
  request_id VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_temperature_rule_scope(adapter_id,building_id,source_scope,model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 提供双温度草稿；启用模板及指定建筑的数据源仍经过审批，不静默升级存量产品。
INSERT INTO biz_device_product
 (product_id,product_code,product_name,manufacturer,model,equipment_type_code,expected_profile_code,identity_type,status)
VALUES ('PRODUCT_DAIKIN_TEMPERATURE','DAIKIN_INDOOR_TEMPERATURE_V2','大金内机室温与设定温度','大金',NULL,
 'IDU','DAIKIN_INDOOR_V2','DAIKIN_UNIT','DRAFT');
INSERT INTO biz_product_point_template
 (template_point_id,product_id,metric_code,point_name_template,suffix_code,unit,min_value,max_value,for_calc,required_flag,sort_order,status)
VALUES ('TPL_DAIKIN_ROOM_TEMP','PRODUCT_DAIKIN_TEMPERATURE','roomTemp','室内温度','roomTemp','°C',NULL,NULL,0,1,10,1),
 ('TPL_DAIKIN_SET_TEMP','PRODUCT_DAIKIN_TEMPERATURE','temperature','设定温度','temperature','°C',NULL,NULL,0,1,20,1);
CREATE TABLE IF NOT EXISTS biz_temperature_binding (
  identity_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  metric_code VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  equipment_id VARCHAR(32) NOT NULL,
  point_id VARCHAR(32) NOT NULL,
  alias_id VARCHAR(32) NOT NULL,
  template_product_id VARCHAR(32) NOT NULL,
  snapshot_json LONGTEXT NOT NULL,
  request_id VARCHAR(32) NOT NULL,
  effective_at_ms BIGINT NOT NULL,
  PRIMARY KEY(identity_id,metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS biz_temperature_job (
  job_id VARCHAR(32) PRIMARY KEY,
  submitted_by BIGINT NOT NULL,
  idempotency_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  request_hash CHAR(64) NOT NULL,
  created_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_temperature_job_idempotency(submitted_by,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS biz_temperature_job_item (
  job_id VARCHAR(32) NOT NULL,
  pending_id VARCHAR(32) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  command_json LONGTEXT NOT NULL,
  request_id VARCHAR(32),
  submission_status VARCHAR(32) NOT NULL,
  error_message VARCHAR(200),
  PRIMARY KEY(job_id,pending_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
