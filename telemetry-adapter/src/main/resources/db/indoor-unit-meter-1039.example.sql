-- 空调内机 1039 七测点协议样例。profile 默认停用，正式接入核对 Topic 唯一匹配及设备身份后再启用。
-- 报文未提供设备时间和序号，timestamp_path/seq_path 保持 NULL，由适配器如实标记接收时间源。
USE `iot_adapter`;

INSERT INTO `iot_protocol_profile`
(`profile_id`, `profile_code`, `profile_version`, `source_topic`,
 `device_identity_type`, `device_identity_path`,
 `protocol_version_path`, `expected_protocol_version`,
 `timestamp_path`, `seq_path`, `max_ack_mode`, `correlation_policy`, `enabled`)
VALUES
('IDU_METER_1039_V1', 'INDOOR_UNIT_METER_1039', 1, 'device/raw/energy/up',
 'SN', '/SN', '/param/ID255/M', '1039', NULL, NULL, 'EVIDENCE_ONLY', 'NONE', 0);

INSERT INTO `iot_protocol_field_mapping`
(`mapping_id`, `profile_id`, `source_path`, `metric_code`, `value_type`,
 `source_unit`, `target_unit`, `scale`, `offset_value`, `required_flag`, `enabled`, `sort_order`)
VALUES
('IDU_1039_U',   'IDU_METER_1039_V1', '/param/ID255/1#/U',   'VOLTAGE',                'DECIMAL', 'V',   'V',   1, 0, 1, 1, 10),
('IDU_1039_I',   'IDU_METER_1039_V1', '/param/ID255/1#/I',   'CURRENT',                'DECIMAL', 'A',   'A',   1, 0, 1, 1, 20),
('IDU_1039_P',   'IDU_METER_1039_V1', '/param/ID255/1#/P',   'POWER',                  'DECIMAL', 'kW',  'kW',  1, 0, 1, 1, 30),
('IDU_1039_PF',  'IDU_METER_1039_V1', '/param/ID255/1#/Pf',  'POWER_FACTOR',           'DECIMAL', '1',   '1',   1, 0, 1, 1, 40),
('IDU_1039_F',   'IDU_METER_1039_V1', '/param/ID255/1#/F',   'FREQUENCY',              'DECIMAL', 'Hz',  'Hz',  1, 0, 1, 1, 50),
('IDU_1039_EPP', 'IDU_METER_1039_V1', '/param/ID255/1#/EPP', 'POSITIVE_ENERGY',        'DECIMAL', 'kWh', 'kWh', 1, 0, 1, 1, 60),
('IDU_1039_EPN', 'IDU_METER_1039_V1', '/param/ID255/1#/EPN', 'NEGATIVE_ENERGY',        'DECIMAL', 'kWh', 'kWh', 1, 0, 1, 1, 70);
