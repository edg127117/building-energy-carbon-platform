INSERT INTO sys_user (id, username, password, nickname, status, del_flag)
VALUES (1, 'admin', '123456', '超级管理员', 1, 0);

INSERT INTO sys_role (id, role_key, role_name, status, data_scope) VALUES
(10, 'BUILDING_OWNER', '建筑业主', 1, 'BUILDING'),
(11, 'ENERGY_MANAGER', '能效管理方', 1, 'BUILDING'),
(12, 'THIRD_PARTY', '对方开发', 1, 'BUILDING'),
(13, 'PLATFORM_ADMIN', '己方管理', 1, 'ALL');

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 13);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, visible, status, sort_order) VALUES
(100, 0, '中央空调调适', 'M', '/hvac', 1, 1, 1),
(101, 100, 'HVAC 能效大屏', 'C', '/hvac-demo', 1, 1, 1),
(200, 0, '系统管理', 'M', '/system', 1, 1, 2),
(210, 200, '人员与角色', 'M', '/system/identity', 1, 1, 1),
(211, 210, '用户管理', 'C', '/system/users', 1, 1, 1),
(212, 210, '角色权限', 'C', '/system/roles', 1, 1, 2),
(220, 200, '建筑权限', 'M', '/system/access', 1, 1, 2),
(223, 220, '建筑授权', 'C', '/system/building-access', 1, 1, 3),
(240, 200, '后台配置', 'M', '/system/config', 1, 1, 4),
(241, 240, '菜单管理', 'C', '/system/menus', 1, 1, 1),
(250, 200, '资产管理', 'M', '/system/assets', 1, 1, 3),
(251, 250, '建筑与空间', 'C', '/system/buildings', 1, 1, 1),
(252, 250, '设备与测点', 'C', '/system/devices', 1, 1, 2);

INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(10,100),(10,101),
(11,100),(11,101),
(13,100),(13,101),(13,200),(13,210),(13,211),(13,212),(13,220),(13,223),(13,240),(13,241),
(13,250),(13,251),(13,252);

INSERT INTO building (building_id, building_name, building_code, del_flag) VALUES
('BLD001', '一号楼', 'B001', 0),
('BLD002', '二号楼', 'B002', 0);

INSERT INTO biz_space
(space_id, building_id, parent_space_id, space_name, space_code, space_type, del_flag)
VALUES
('SPACE001', 'BLD001', NULL, '一号楼冷源机房', 'MR01', 'ROOM', 0),
('SPACE002', 'BLD002', NULL, '二号楼冷源机房', 'MR01', 'ROOM', 0);

INSERT INTO biz_system_group
(system_group_id, system_group_code, building_id, system_type, system_group_name, del_flag)
VALUES
('GROUP001', 'SG001', 'BLD001', 'HVAC', '一号楼冷热源系统', 0),
('GROUP002', 'SG001', 'BLD002', 'HVAC', '二号楼冷热源系统', 0);

INSERT INTO biz_equipment_type
(type_code, type_name, asset_code_prefix, equip_category, standard_source, status)
VALUES
('WCR', '水冷式冷水机组', 'WCR', 'CHILLER', 'HANDOFF', 1),
('WCT', '冷却塔', 'TOWER', 'TOWER', 'FREEZE_EXTENSION', 1),
('WCP', '水泵', 'PUMP', 'PUMP', 'FREEZE_EXTENSION', 1),
('AHU', '空气处理机组', 'AHU', 'AHU', 'FREEZE_EXTENSION', 1),
('Bh', '热水锅炉', 'Bh', 'BOILER', 'HANDOFF', 1),
('Bs', '蒸汽锅炉', 'Bs', 'BOILER', 'HANDOFF', 1);

INSERT INTO biz_point_naming_rule
(rule_id, standard_version, family_code, component_code, code_template, standard_source, status)
VALUES
('RULE_WCR_MAIN', 'HANDOFF_V1', 'WCR', 'MAIN', 'WCR[n]', 'HANDOFF', 1),
('RULE_WCR_PC', 'HANDOFF_V1', 'WCR', 'Pc', 'WCR[n]_Pc', 'HANDOFF', 1),
('RULE_WCR_CT', 'HANDOFF_V1', 'WCR', 'CT', 'WCR[n]_CT', 'HANDOFF', 1),
('RULE_WCR_PCD', 'HANDOFF_V1', 'WCR', 'Pcd', 'WCR[n]_Pcd', 'HANDOFF', 1),
('RULE_AHU_MAIN', 'FREEZE_V1', 'AHU', 'MAIN', 'AHU[n]', 'FREEZE_EXTENSION', 1),
('RULE_DBO_ENV', 'HANDOFF_V1', 'DBO', 'ENV', 'DBO', 'HANDOFF', 1),
('RULE_RHO_ENV', 'HANDOFF_V1', 'RHO', 'ENV', 'RHO', 'HANDOFF', 1);

INSERT INTO biz_equipment
(equip_id, equip_code, equip_name, type_code, equip_category, system_group_id, building_id, space_id, del_flag)
VALUES
('EQUIP_WCR_B1', 'WCR1', '一号冷水机组', 'WCR', 'CHILLER', 'GROUP001', 'BLD001', 'SPACE001', 0),
('EQUIP_WCR_B2', 'WCR1', '二号冷水机组', 'WCR', 'CHILLER', 'GROUP002', 'BLD002', 'SPACE002', 0),
('EQUIP_TOWER_B1', 'TOWER1', '一号冷却塔', 'WCT', 'TOWER', 'GROUP001', 'BLD001', 'SPACE001', 0),
('EQUIP_PUMP_B1', 'PUMP1', '一号冷冻水泵', 'WCP', 'PUMP', 'GROUP001', 'BLD001', 'SPACE001', 0),
('EQUIP_AHU_B1', 'AHU1', '一号空气处理机组', 'AHU', 'AHU', 'GROUP001', 'BLD001', 'SPACE001', 0);

INSERT INTO biz_device_identity
(identity_id, identity_type, identity_value, equip_id, building_id, expected_profile_code, status)
VALUES
('IDENTITY_WCR_B1', 'MAC', 'TEST-MAC-WCR-B1', 'EQUIP_WCR_B1', 'BLD001', 'HVAC_DEVICE_V1', 1);

-- 标准测点使用交底规范；冻结书19点编码放入别名表。
INSERT INTO biz_data_point
(point_id, point_code, point_name, building_id, system_group_id, equip_id,
 naming_rule_id, family_code, component_code, suffix_code,
 data_type, unit, is_for_calc, status, del_flag)
VALUES
('POINT001', 'WCR1_TWin', '一号机组冷冻水进水温度', 'BLD001', 'GROUP001', 'EQUIP_WCR_B1', 'RULE_WCR_MAIN', 'WCR', 'MAIN', 'TWin', 'ANALOG', '℃', 1, 'ONLINE', 0),
('POINT002', 'WCR1_TWout', '一号机组冷冻水出水温度', 'BLD001', 'GROUP001', 'EQUIP_WCR_B1', 'RULE_WCR_MAIN', 'WCR', 'MAIN', 'TWout', 'ANALOG', '℃', 1, 'ONLINE', 0),
('POINT003', 'WCR1_GW', '一号机组冷冻水流量', 'BLD001', 'GROUP001', 'EQUIP_WCR_B1', 'RULE_WCR_MAIN', 'WCR', 'MAIN', 'GW', 'ANALOG', 'm³/h', 1, 'ONLINE', 0),
('POINT004', 'WCR1_PPE', '一号机组瞬时功率', 'BLD001', 'GROUP001', 'EQUIP_WCR_B1', 'RULE_WCR_MAIN', 'WCR', 'MAIN', 'PPE', 'ANALOG', 'kW', 1, 'ONLINE', 0),
('POINT005', 'WCR1_Voltage', '一号机组压缩机电压', 'BLD001', 'GROUP001', 'EQUIP_WCR_B1', 'RULE_WCR_MAIN', 'WCR', 'MAIN', 'Voltage', 'ANALOG', 'V', 1, 'ONLINE', 0),
('POINT006', 'WCR1_Current', '一号机组压缩机电流', 'BLD001', 'GROUP001', 'EQUIP_WCR_B1', 'RULE_WCR_MAIN', 'WCR', 'MAIN', 'Current', 'ANALOG', 'A', 1, 'ONLINE', 0),
('POINT007', 'WCR1_PF', '一号机组功率因数', 'BLD001', 'GROUP001', 'EQUIP_WCR_B1', 'RULE_WCR_MAIN', 'WCR', 'MAIN', 'PF', 'ANALOG', '1', 1, 'ONLINE', 0),
('POINT008', 'WCR1_CT_TWin', '一号冷却塔冷却水进水温度', 'BLD001', 'GROUP001', 'EQUIP_TOWER_B1', 'RULE_WCR_CT', 'WCR', 'CT', 'TWin', 'ANALOG', '℃', 1, 'ONLINE', 0),
('POINT009', 'WCR1_CT_TWout', '一号冷却塔冷却水出水温度', 'BLD001', 'GROUP001', 'EQUIP_TOWER_B1', 'RULE_WCR_CT', 'WCR', 'CT', 'TWout', 'ANALOG', '℃', 1, 'ONLINE', 0),
('POINT010', 'WCR1_CT_TWB', '一号冷却塔室外湿球温度', 'BLD001', 'GROUP001', 'EQUIP_TOWER_B1', 'RULE_WCR_CT', 'WCR', 'CT', 'TWB', 'ANALOG', '℃', 1, 'ONLINE', 0),
('POINT011', 'WCR1_Pc_GW', '一号冷水泵流量', 'BLD001', 'GROUP001', 'EQUIP_PUMP_B1', 'RULE_WCR_PC', 'WCR', 'Pc', 'GW', 'ANALOG', 'm³/h', 1, 'ONLINE', 0),
('POINT012', 'WCR1_Pc_Pout', '一号冷水泵出口压力', 'BLD001', 'GROUP001', 'EQUIP_PUMP_B1', 'RULE_WCR_PC', 'WCR', 'Pc', 'Pout', 'ANALOG', 'Pa', 1, 'ONLINE', 0),
('POINT013', 'WCR1_Pc_Pin', '一号冷水泵入口压力', 'BLD001', 'GROUP001', 'EQUIP_PUMP_B1', 'RULE_WCR_PC', 'WCR', 'Pc', 'Pin', 'ANALOG', 'Pa', 1, 'ONLINE', 0),
('POINT014', 'WCR1_Pc_Z', '一号冷水泵进出口压力表高度差', 'BLD001', 'GROUP001', 'EQUIP_PUMP_B1', 'RULE_WCR_PC', 'WCR', 'Pc', 'Z', 'ANALOG', 'm', 1, 'ONLINE', 0),
('POINT015', 'WCR1_Pc_PPE', '一号冷水泵输入功率', 'BLD001', 'GROUP001', 'EQUIP_PUMP_B1', 'RULE_WCR_PC', 'WCR', 'Pc', 'PPE', 'ANALOG', 'kW', 1, 'ONLINE', 0),
('POINT016', 'AHU1_TotalPress', '一号AHU风机全压值', 'BLD001', 'GROUP001', 'EQUIP_AHU_B1', 'RULE_AHU_MAIN', 'AHU', 'MAIN', 'TotalPress', 'ANALOG', 'Pa', 1, 'ONLINE', 0),
('POINT017', 'AHU1_EtaT', '一号AHU风机总效率', 'BLD001', 'GROUP001', 'EQUIP_AHU_B1', 'RULE_AHU_MAIN', 'AHU', 'MAIN', 'EtaT', 'ANALOG', '%', 1, 'ONLINE', 0),
('POINT018', 'DBO', '室外干球温度', 'BLD001', NULL, NULL, 'RULE_DBO_ENV', 'DBO', 'ENV', 'TDB', 'ANALOG', '℃', 1, 'ONLINE', 0),
('POINT019', 'RHO', '室外相对湿度', 'BLD001', NULL, NULL, 'RULE_RHO_ENV', 'RHO', 'ENV', 'RH', 'ANALOG', '%', 1, 'ONLINE', 0),
('POINT020', 'WCR1_TWin', '二号楼机组进水温度', 'BLD002', 'GROUP002', 'EQUIP_WCR_B2', 'RULE_WCR_MAIN', 'WCR', 'MAIN', 'TWin', 'ANALOG', '℃', 0, 'ONLINE', 0);

-- 19 个继承别名已通过系统迁移纳入正式来源；二号楼保留独立测试来源，避免跨建筑串源。
INSERT INTO biz_data_source
(source_id, source_code, source_name, building_id, source_category, transport_type,
 status, description, config_revision, runtime_revision, create_by, update_by)
VALUES
('SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', '冻结版 MQTT HVAC 来源', 'BLD001',
 'DEVICE_ACCESS', 'MQTT', 'ENABLED', '系统迁移纳管现有 HVAC 19 测点来源', 1, 1, NULL, NULL),
('SOURCE_TEST_BLD002', 'MQTT_BLD002_TEST', '二号楼测试 MQTT 来源', 'BLD002',
 'DEVICE_ACCESS', 'MQTT', 'ENABLED', 'H2 隔离测试来源', 1, 1, NULL, NULL);

INSERT INTO biz_point_alias
(alias_id, building_id, source_id, source_system, source_point_code, point_id, status, revision)
VALUES
('ALIAS001', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'WCR1_TWin', 'POINT001', 1, 1),
('ALIAS002', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'WCR1_TWout', 'POINT002', 1, 1),
('ALIAS003', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'WCR1_Flow', 'POINT003', 1, 1),
('ALIAS004', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'WCR1_PPE', 'POINT004', 1, 1),
('ALIAS005', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'WCR1_Voltage', 'POINT005', 1, 1),
('ALIAS006', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'WCR1_Current', 'POINT006', 1, 1),
('ALIAS007', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'WCR1_PF', 'POINT007', 1, 1),
('ALIAS008', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'TOWER1_TCWin', 'POINT008', 1, 1),
('ALIAS009', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'TOWER1_TCWout', 'POINT009', 1, 1),
('ALIAS010', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'TOWER1_TWB', 'POINT010', 1, 1),
('ALIAS011', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'PUMP1_Flow', 'POINT011', 1, 1),
('ALIAS012', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'PUMP1_Pout', 'POINT012', 1, 1),
('ALIAS013', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'PUMP1_Pin', 'POINT013', 1, 1),
('ALIAS014', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'PUMP1_Z', 'POINT014', 1, 1),
('ALIAS015', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'PUMP1_Power', 'POINT015', 1, 1),
('ALIAS016', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'AHU1_TotalPress', 'POINT016', 1, 1),
('ALIAS017', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'AHU1_EtaT', 'POINT017', 1, 1),
('ALIAS018', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'DBO_TDB', 'POINT018', 1, 1),
('ALIAS019', 'BLD001', 'SOURCE_MQTT_FREEZE_V1', 'MQTT_FREEZE_V1', 'DBO_RH', 'POINT019', 1, 1),
('ALIAS020', 'BLD002', 'SOURCE_TEST_BLD002', 'MQTT_FREEZE_V1', 'WCR1_TWin', 'POINT020', 1, 1);

INSERT INTO biz_collection_policy
(policy_id, source_id, alias_id, building_id, active_version_id, draft_version_id, create_by)
SELECT CONCAT('POLICY', RIGHT(alias_id, 3)), source_id, alias_id, building_id,
       CONCAT('POLICY_VERSION', RIGHT(alias_id, 3)), NULL, NULL
FROM biz_point_alias
WHERE building_id = 'BLD001' AND source_id = 'SOURCE_MQTT_FREEZE_V1';

INSERT INTO biz_collection_policy_version
(version_id, policy_id, version_no, status, enabled_flag,
 expected_interval_seconds, allowed_delay_seconds, time_semantics,
 raw_retention_mode, raw_retention_days, minute_retention_mode, minute_retention_days,
 source_code_snapshot, source_point_code_snapshot, point_id_snapshot, point_code_snapshot,
 data_type_snapshot, unit_snapshot, change_type, change_source, change_reason, revision,
 created_by, published_by, published_at, effective_from, effective_to, retired_at)
SELECT CONCAT('POLICY_VERSION', RIGHT(a.alias_id, 3)),
       CONCAT('POLICY', RIGHT(a.alias_id, 3)),
       1, 'ACTIVE', 1, 60, 30, 'DEVICE_EVENT_TIME',
       'FIXED_DAYS', 90, 'LONG_TERM', NULL,
       'MQTT_FREEZE_V1', a.source_point_code, p.point_id, p.point_code,
       p.data_type, p.unit, 'INITIAL_MIGRATION', 'INITIAL_MIGRATION',
       '系统迁移：纳管现有 MQTT_FREEZE_V1 测点', 1,
       NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL
FROM biz_point_alias a
JOIN biz_data_point p ON p.point_id = a.point_id AND p.building_id = a.building_id
WHERE a.building_id = 'BLD001' AND a.source_id = 'SOURCE_MQTT_FREEZE_V1';

INSERT INTO biz_collection_policy
(policy_id, source_id, alias_id, building_id, active_version_id, draft_version_id, create_by)
VALUES
('POLICY020', 'SOURCE_TEST_BLD002', 'ALIAS020', 'BLD002', 'POLICY_VERSION020', NULL, NULL);

INSERT INTO biz_collection_policy_version
(version_id, policy_id, version_no, status, enabled_flag,
 expected_interval_seconds, allowed_delay_seconds, time_semantics,
 raw_retention_mode, raw_retention_days, minute_retention_mode, minute_retention_days,
 source_code_snapshot, source_point_code_snapshot, point_id_snapshot, point_code_snapshot,
 data_type_snapshot, unit_snapshot, change_type, change_source, change_reason, revision,
 created_by, published_by, published_at, effective_from, effective_to, retired_at)
VALUES
('POLICY_VERSION020', 'POLICY020', 1, 'ACTIVE', 1, 15, 300, 'DEVICE_EVENT_TIME',
 'FIXED_DAYS', 14, 'FIXED_DAYS', 365,
 'MQTT_BLD002_TEST', 'WCR1_TWin', 'POINT020', 'WCR1_TWin', 'ANALOG', '℃',
 'CREATE', 'MANUAL', 'H2 验证可配置的非默认采集策略', 1,
 NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL);

INSERT INTO biz_collection_config_audit_log
(audit_id, building_id, actor_type, operator_id, action_type, object_type,
 object_id, version_id, before_summary, after_summary, result)
VALUES
('AUDIT_MIGRATION_SOURCE', 'BLD001', 'SYSTEM_MIGRATION', NULL, 'INITIAL_MIGRATION',
 'DATA_SOURCE', 'SOURCE_MQTT_FREEZE_V1', NULL, 'legacyAliases=19',
 'status=ENABLED; policies=19; runtimeRevision=1', 'SUCCESS');

INSERT INTO biz_collection_config_audit_log
(audit_id, building_id, actor_type, operator_id, action_type, object_type,
 object_id, version_id, before_summary, after_summary, result)
SELECT CONCAT('AUDIT_', CONCAT('POLICY', RIGHT(alias_id, 3))),
       building_id, 'SYSTEM_MIGRATION', NULL, 'INITIAL_MIGRATION', 'COLLECTION_POLICY',
       CONCAT('POLICY', RIGHT(alias_id, 3)), CONCAT('POLICY_VERSION', RIGHT(alias_id, 3)),
       'legacyAlias=ENABLED',
       'activeVersion=1; intervalSeconds=60; allowedDelaySeconds=30', 'SUCCESS'
FROM biz_point_alias
WHERE building_id = 'BLD001' AND source_id = 'SOURCE_MQTT_FREEZE_V1';

INSERT INTO biz_indicator
(indicator_id, building_id, indicator_code, scope_type, scope_id,
 equip_id, system_group_id, status)
VALUES
('INDICATOR_WCR_COP_B1','BLD001','WCR_COP','EQUIPMENT',
 'EQUIP_WCR_B1','EQUIP_WCR_B1','GROUP001',1),
('INDICATOR_TOWER_EFF_B1','BLD001','TOWER_EFF','EQUIPMENT',
 'EQUIP_TOWER_B1','EQUIP_TOWER_B1','GROUP001',1),
('INDICATOR_PUMP_EFF_B1','BLD001','PUMP_EFF','EQUIPMENT',
 'EQUIP_PUMP_B1','EQUIP_PUMP_B1','GROUP001',1),
('INDICATOR_AHU_EFF_B1','BLD001','AHU_POW_EFF','EQUIPMENT',
 'EQUIP_AHU_B1','EQUIP_AHU_B1','GROUP001',1);

-- 能源字典研发基线全部保持待专业确认；测试必须显式审核后才能建立正式绑定。
INSERT INTO biz_energy_item (item_id,item_code,created_by) VALUES
('EI_BITUMINOUS_COAL','BITUMINOUS_COAL',0),('EI_ANTHRACITE','ANTHRACITE',0),
('EI_LIGNITE','LIGNITE',0),('EI_DIESEL','DIESEL',0),('EI_GASOLINE','GASOLINE',0),
('EI_FUEL_OIL','FUEL_OIL',0),('EI_KEROSENE','KEROSENE',0),('EI_LPG','LPG',0),
('EI_LNG','LNG',0),('EI_NATURAL_GAS','NATURAL_GAS',0),
('EI_COKE_OVEN_GAS','COKE_OVEN_GAS',0),('EI_ELECTRICITY','ELECTRICITY',0),
('EI_HEAT','HEAT',0);

INSERT INTO biz_energy_item_version
(version_id,item_id,version_no,item_name,compatible_category,status,source_type,
 source_reference,effective_from,config_revision,created_by) VALUES
('EIV_BITUMINOUS_COAL_1','EI_BITUMINOUS_COAL',1,'烟煤','FUEL','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_ANTHRACITE_1','EI_ANTHRACITE',1,'无烟煤','FUEL','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_LIGNITE_1','EI_LIGNITE',1,'褐煤','FUEL','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_DIESEL_1','EI_DIESEL',1,'柴油','FUEL','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_GASOLINE_1','EI_GASOLINE',1,'汽油','FUEL','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_FUEL_OIL_1','EI_FUEL_OIL',1,'燃料油','FUEL','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_KEROSENE_1','EI_KEROSENE',1,'一般煤油','FUEL','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_LPG_1','EI_LPG',1,'液化石油气','FUEL','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_LNG_1','EI_LNG',1,'液化天然气','FUEL','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_NATURAL_GAS_1','EI_NATURAL_GAS',1,'天然气','NATURAL_GAS','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_COKE_OVEN_GAS_1','EI_COKE_OVEN_GAS',1,'焦炉煤气','FUEL','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_ELECTRICITY_1','EI_ELECTRICITY',1,'电力','ELECTRICITY','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0),
('EIV_HEAT_1','EI_HEAT',1,'外购热力','HEAT','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，生产不可用','2000-01-01',0,0);

INSERT INTO biz_energy_item_version_scope (version_id,usage_scope) VALUES
('EIV_BITUMINOUS_COAL_1','STATIONARY_COMBUSTION'),('EIV_ANTHRACITE_1','STATIONARY_COMBUSTION'),
('EIV_LIGNITE_1','STATIONARY_COMBUSTION'),('EIV_DIESEL_1','STATIONARY_COMBUSTION'),
('EIV_GASOLINE_1','STATIONARY_COMBUSTION'),('EIV_FUEL_OIL_1','STATIONARY_COMBUSTION'),
('EIV_KEROSENE_1','STATIONARY_COMBUSTION'),('EIV_LPG_1','STATIONARY_COMBUSTION'),
('EIV_LNG_1','STATIONARY_COMBUSTION'),('EIV_NATURAL_GAS_1','STATIONARY_COMBUSTION'),
('EIV_COKE_OVEN_GAS_1','STATIONARY_COMBUSTION'),
('EIV_ELECTRICITY_1','PURCHASED_ELECTRICITY'),('EIV_HEAT_1','PURCHASED_HEAT');

INSERT INTO biz_measurement_unit (unit_id,unit_code,created_by) VALUES
('EU_KW','KW',0),('EU_KWH','KWH',0),('EU_MWH','MWH',0),('EU_MJ','MJ',0),('EU_GJ','GJ',0),
('EU_M3','M3',0),('EU_NM3','NM3',0),('EU_TEN_THOUSAND_NM3','TEN_THOUSAND_NM3',0),
('EU_KG','KG',0),('EU_T','T',0),('EU_KGCE','KGCE',0),('EU_TCE','TCE',0);

INSERT INTO biz_measurement_unit_version
(version_id,unit_id,version_no,symbol,unit_name,dimension_code,canonical_unit_code,scale_factor,
 conversion_type,standard_condition_code,decimal_precision,status,source_type,source_reference,
 effective_from,config_revision,created_by) VALUES
('EUV_KW_1','EU_KW',1,'kW','千瓦','POWER','KW',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_KWH_1','EU_KWH',1,'kWh','千瓦时','ENERGY','KWH',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_MWH_1','EU_MWH',1,'MWh','兆瓦时','ENERGY','KWH',1000,'FIXED_SCALE',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_MJ_1','EU_MJ',1,'MJ','兆焦','ENERGY','MJ',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_GJ_1','EU_GJ',1,'GJ','吉焦','ENERGY','MJ',1000,'FIXED_SCALE',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_M3_1','EU_M3',1,'m³','立方米','ACTUAL_VOLUME','M3',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_NM3_1','EU_NM3',1,'Nm³','标准立方米','NORMAL_VOLUME','NM3',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，标准状态仍待确认','2000-01-01',0,0),
('EUV_TEN_THOUSAND_NM3_1','EU_TEN_THOUSAND_NM3',1,'万Nm³','万标准立方米','NORMAL_VOLUME','NM3',10000,'FIXED_SCALE',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，标准状态仍待确认','2000-01-01',0,0),
('EUV_KG_1','EU_KG',1,'kg','千克','MASS','KG',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_T_1','EU_T',1,'t','吨','MASS','KG',1000,'FIXED_SCALE',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，待专业确认','2000-01-01',0,0),
('EUV_KGCE_1','EU_KGCE',1,'kgce','千克标准煤','STANDARD_COAL_EQUIVALENT','KGCE',1,'IDENTITY',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，仅计算输出','2000-01-01',0,0),
('EUV_TCE_1','EU_TCE',1,'tce','吨标准煤','STANDARD_COAL_EQUIVALENT','KGCE',1000,'FIXED_SCALE',NULL,6,'PENDING_EXPERT','STANDARD','第七闭环设计§5，仅计算输出','2000-01-01',0,0);

-- 折标基线沿用用户提供样例，但全部保持待专业确认和研发模拟用途。
INSERT INTO biz_standard_coal_lhv (lhv_id,lhv_code,created_by) VALUES
('SCL_STANDARD','STANDARD_COAL_LHV',0);

INSERT INTO biz_standard_coal_lhv_version
(version_id,lhv_id,version_no,lhv_value,energy_unit_version_id,coal_unit_version_id,
 parameter_unit,status,source_type,source_reference,effective_from,config_revision,created_by) VALUES
('SCLV_STANDARD_1','SCL_STANDARD',1,29.3076,'EUV_GJ_1','EUV_TCE_1','GJ_PER_TCE',
 'PENDING_EXPERT','STANDARD','历史标准折算截图与第七闭环设计§9.2，仅研发基线，待专业确认，生产不可用',
 '2000-01-01',0,0);

INSERT INTO biz_energy_conversion_formula (formula_id,formula_code,created_by) VALUES
('ECF_LHV_T','LHV_PER_T',0),
('ECF_LHV_10KNM3','LHV_PER_TEN_THOUSAND_NM3',0),
('ECF_ELECTRICITY_CAL','ELECTRICITY_CALORIFIC_EQUIVALENT',0),
('ECF_ELECTRICITY_PRIMARY','ELECTRICITY_PRIMARY_EQUIVALENT',0),
('ECF_HEAT_CAL','PURCHASED_HEAT_CALORIFIC_EQUIVALENT',0);

INSERT INTO biz_energy_conversion_formula_version
(version_id,formula_id,version_no,method,perspective,algorithm_code,
 applicable_input_unit_version_id,result_unit_version_id,parameter_unit,status,source_type,
 source_reference,effective_from,config_revision,created_by) VALUES
('ECFV_LHV_T_1','ECF_LHV_T',1,'LOWER_HEATING_VALUE','CALORIFIC_EQUIVALENT','LOWER_HEATING_VALUE_V1','EUV_T_1','EUV_TCE_1','GJ_PER_INPUT_UNIT','PENDING_EXPERT','STANDARD','第七闭环设计§9.3，待专业确认','2000-01-01',0,0),
('ECFV_LHV_10KNM3_1','ECF_LHV_10KNM3',1,'LOWER_HEATING_VALUE','CALORIFIC_EQUIVALENT','LOWER_HEATING_VALUE_V1','EUV_TEN_THOUSAND_NM3_1','EUV_TCE_1','GJ_PER_INPUT_UNIT','PENDING_EXPERT','STANDARD','第七闭环设计§9.3，待专业确认','2000-01-01',0,0),
('ECFV_ELECTRICITY_CAL_1','ECF_ELECTRICITY_CAL',1,'ENERGY_EQUIVALENT','CALORIFIC_EQUIVALENT','ENERGY_EQUIVALENT_V1','EUV_KWH_1','EUV_TCE_1','MJ_PER_INPUT_UNIT','PENDING_EXPERT','STANDARD','第七闭环设计§9.5，待专业确认','2000-01-01',0,0),
('ECFV_ELECTRICITY_PRIMARY_1','ECF_ELECTRICITY_PRIMARY',1,'ENERGY_EQUIVALENT','PRIMARY_EQUIVALENT','ENERGY_EQUIVALENT_V1','EUV_KWH_1','EUV_TCE_1','MJ_PER_INPUT_UNIT','PENDING_EXPERT','STANDARD','第七闭环设计§9.5，等价值系数待专业确认','2000-01-01',0,0),
('ECFV_HEAT_CAL_1','ECF_HEAT_CAL',1,'ENERGY_EQUIVALENT','CALORIFIC_EQUIVALENT','ENERGY_EQUIVALENT_V1','EUV_GJ_1','EUV_TCE_1','MJ_PER_INPUT_UNIT','PENDING_EXPERT','STANDARD','第七闭环设计§9.4，待专业确认','2000-01-01',0,0);

INSERT INTO biz_energy_conversion_parameter
(parameter_id,parameter_code,energy_item_id,created_by) VALUES
('ECP_BITUMINOUS_LHV','BITUMINOUS_COAL_LHV','EI_BITUMINOUS_COAL',0),
('ECP_ANTHRACITE_LHV','ANTHRACITE_LHV','EI_ANTHRACITE',0),
('ECP_LIGNITE_LHV','LIGNITE_LHV','EI_LIGNITE',0),
('ECP_DIESEL_LHV','DIESEL_LHV','EI_DIESEL',0),
('ECP_GASOLINE_LHV','GASOLINE_LHV','EI_GASOLINE',0),
('ECP_FUEL_OIL_LHV','FUEL_OIL_LHV','EI_FUEL_OIL',0),
('ECP_KEROSENE_LHV','KEROSENE_LHV','EI_KEROSENE',0),
('ECP_LPG_LHV','LPG_LHV','EI_LPG',0),
('ECP_LNG_LHV','LNG_LHV','EI_LNG',0),
('ECP_NATURAL_GAS_LHV','NATURAL_GAS_LHV','EI_NATURAL_GAS',0),
('ECP_COKE_OVEN_GAS_LHV','COKE_OVEN_GAS_LHV','EI_COKE_OVEN_GAS',0),
('ECP_ELECTRICITY_CAL','ELECTRICITY_CALORIFIC_FACTOR','EI_ELECTRICITY',0),
('ECP_HEAT_CAL','PURCHASED_HEAT_CALORIFIC_FACTOR','EI_HEAT',0);

INSERT INTO biz_energy_conversion_parameter_version
(version_id,parameter_id,version_no,energy_item_version_id,formula_version_id,
 parameter_value,parameter_unit,standard_coal_lhv_version_id,consumption_scope,region_code,
 usage_scope,status,source_type,source_reference,effective_from,config_revision,created_by) VALUES
('ECPV_BITUMINOUS_LHV_1','ECP_BITUMINOUS_LHV',1,'EIV_BITUMINOUS_COAL_1','ECFV_LHV_T_1',22.400,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_ANTHRACITE_LHV_1','ECP_ANTHRACITE_LHV',1,'EIV_ANTHRACITE_1','ECFV_LHV_T_1',23.200,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_LIGNITE_LHV_1','ECP_LIGNITE_LHV',1,'EIV_LIGNITE_1','ECFV_LHV_T_1',14.100,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_DIESEL_LHV_1','ECP_DIESEL_LHV',1,'EIV_DIESEL_1','ECFV_LHV_T_1',43.330,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_GASOLINE_LHV_1','ECP_GASOLINE_LHV',1,'EIV_GASOLINE_1','ECFV_LHV_T_1',44.800,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_FUEL_OIL_LHV_1','ECP_FUEL_OIL_LHV',1,'EIV_FUEL_OIL_1','ECFV_LHV_T_1',40.190,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_KEROSENE_LHV_1','ECP_KEROSENE_LHV',1,'EIV_KEROSENE_1','ECFV_LHV_T_1',44.750,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_LPG_LHV_1','ECP_LPG_LHV',1,'EIV_LPG_1','ECFV_LHV_T_1',47.310,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_LNG_LHV_1','ECP_LNG_LHV',1,'EIV_LNG_1','ECFV_LHV_T_1',41.868,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_NATURAL_GAS_LHV_1','ECP_NATURAL_GAS_LHV',1,'EIV_NATURAL_GAS_1','ECFV_LHV_10KNM3_1',389.310,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，标准状态待确认，生产不可用','2000-01-01',0,0),
('ECPV_COKE_OVEN_GAS_LHV_1','ECP_COKE_OVEN_GAS_LHV',1,'EIV_COKE_OVEN_GAS_1','ECFV_LHV_10KNM3_1',173.500,'GJ_PER_INPUT_UNIT','SCLV_STANDARD_1','STATIONARY_COMBUSTION','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','EXCEL','历史Excel样例，仅研发模拟，标准状态待确认，生产不可用','2000-01-01',0,0),
('ECPV_ELECTRICITY_CAL_1','ECP_ELECTRICITY_CAL',1,'EIV_ELECTRICITY_1','ECFV_ELECTRICITY_CAL_1',3.6,'MJ_PER_INPUT_UNIT','SCLV_STANDARD_1','PURCHASED_ELECTRICITY','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','STANDARD','第七闭环设计§9.5，当量值研发基线，待专业确认，生产不可用','2000-01-01',0,0),
('ECPV_HEAT_CAL_1','ECP_HEAT_CAL',1,'EIV_HEAT_1','ECFV_HEAT_CAL_1',1000,'MJ_PER_INPUT_UNIT','SCLV_STANDARD_1','PURCHASED_HEAT','GLOBAL','DEVELOPMENT_SIMULATION','PENDING_EXPERT','STANDARD','第七闭环设计§9.4，外购热力研发基线，待专业确认，生产不可用','2000-01-01',0,0);
