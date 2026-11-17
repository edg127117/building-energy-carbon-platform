-- 台账展示元数据独立于能源分类、公式参数及采集身份；不向真实设备发送命令。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 目标库由已核验的数据源选择，不跨库切换；共享端与独立大屏使用不同业务库名。

CREATE TABLE biz_ledger_type_rule (
  type_code VARCHAR(50) NOT NULL PRIMARY KEY,
  professional_group VARCHAR(20) NOT NULL,
  operation_applicable TINYINT NOT NULL,
  point_component VARCHAR(20),
  point_suffix VARCHAR(20),
  evidence VARCHAR(500) NOT NULL,
  revision BIGINT NOT NULL,
  updated_by BIGINT,
  CHECK (professional_group IN ('POWER','HVAC','RENEWABLE','WATER','ENVIRONMENT','UNCLASSIFIED')),
  CHECK (operation_applicable IN (0,1)),
  CHECK ((point_component IS NULL AND point_suffix IS NULL) OR (point_component IS NOT NULL AND point_suffix IS NOT NULL)),
  CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_ledger_equipment_classification (
  equipment_id VARCHAR(32) NOT NULL PRIMARY KEY,
  professional_group VARCHAR(20),
  evidence VARCHAR(500) NOT NULL,
  revision BIGINT NOT NULL,
  updated_by BIGINT NOT NULL,
  CHECK (professional_group IS NULL OR professional_group IN ('POWER','HVAC','RENEWABLE','WATER','ENVIRONMENT','UNCLASSIFIED')),
  CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_ledger_config_audit (
  audit_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  config_kind VARCHAR(40) NOT NULL,
  target_id VARCHAR(50) NOT NULL,
  changed_by BIGINT NOT NULL,
  changed_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  before_json TEXT NOT NULL,
  after_json TEXT NOT NULL,
  INDEX idx_ledger_audit_target(config_kind,target_id,audit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 只按现有明确类型初始化专业和能力；不因 PUMP 分类就把任意水泵归给排水。
-- 默认展示选择复用现有电表 POWER 和大金 roomTemp 的明确后缀，不改变模板或创建设备测点。
-- 其他类型未确认展示项时留空；缺失或多点匹配必须显示缺失，不能自动取首点。
INSERT INTO biz_ledger_type_rule
 (type_code,professional_group,operation_applicable,point_component,point_suffix,evidence,revision,updated_by)
SELECT type_code,
 CASE WHEN type_code IN ('ELECTRIC_METER_1P','ELECTRIC_METER_3P') THEN 'POWER' ELSE 'HVAC' END,
 CASE WHEN type_code IN ('ELECTRIC_METER_1P','ELECTRIC_METER_3P') THEN 0 ELSE 1 END,
 CASE WHEN type_code IN ('ELECTRIC_METER_1P','ELECTRIC_METER_3P','IDU') THEN 'MAIN' ELSE NULL END,
 CASE WHEN type_code IN ('ELECTRIC_METER_1P','ELECTRIC_METER_3P') THEN 'P' WHEN type_code='IDU' THEN 'roomTemp' ELSE NULL END,
 '台账类型展示基线；设备类型字典及 V44/V57 电表 POWER、V63 大金 roomTemp 模板',1,NULL
FROM biz_equipment_type
WHERE status=1 AND type_code IN ('ELECTRIC_METER_1P','ELECTRIC_METER_3P','IDU','ODU','WCR','WCT','WCP','AHU','Bh','Bs');

UPDATE sys_menu SET menu_name='设备综合台账' WHERE path='/operations/devices/businessDevices';
