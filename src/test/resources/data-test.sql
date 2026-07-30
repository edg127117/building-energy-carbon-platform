INSERT INTO sys_user (id, username, password, nickname, status, del_flag)
VALUES (1, 'admin', '123456', '超级管理员', 1, 0);

INSERT INTO sys_role (id, role_key, role_name, status, data_scope) VALUES
(10, 'BUILDING_OWNER', '建筑业主', 1, 'BUILDING'),
(11, 'ENERGY_MANAGER', '能效管理方', 1, 'BUILDING'),
(12, 'THIRD_PARTY', '对方开发', 1, 'BUILDING'),
(13, 'PLATFORM_ADMIN', '己方管理', 1, 'ALL');

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 13);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, visible, status, sort_order) VALUES
(100, 0, '常规调适', 'M', '/commissioning', 1, 1, 1),
(110, 100, '单机调适', 'M', '/single', 1, 1, 1),
(130, 110, '冷水机组', 'M', '/single/chiller', 1, 1, 1),
(132, 130, 'COP计算', 'C', '/single/chiller/cop', 1, 1, 1),
(200, 0, '系统管理', 'M', '/system', 1, 1, 2),
(211, 200, '用户管理', 'C', '/system/user/list', 1, 1, 1);

INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(10,100),(10,110),(10,130),(10,132),
(11,100),(11,110),(11,130),(11,132),
(13,100),(13,110),(13,130),(13,132),(13,200),(13,211);

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

INSERT INTO biz_point_alias
(alias_id, building_id, source_system, source_point_code, point_id, status)
VALUES
('ALIAS001', 'BLD001', 'MQTT_FREEZE_V1', 'WCR1_TWin', 'POINT001', 1),
('ALIAS002', 'BLD001', 'MQTT_FREEZE_V1', 'WCR1_TWout', 'POINT002', 1),
('ALIAS003', 'BLD001', 'MQTT_FREEZE_V1', 'WCR1_Flow', 'POINT003', 1),
('ALIAS004', 'BLD001', 'MQTT_FREEZE_V1', 'WCR1_PPE', 'POINT004', 1),
('ALIAS005', 'BLD001', 'MQTT_FREEZE_V1', 'WCR1_Voltage', 'POINT005', 1),
('ALIAS006', 'BLD001', 'MQTT_FREEZE_V1', 'WCR1_Current', 'POINT006', 1),
('ALIAS007', 'BLD001', 'MQTT_FREEZE_V1', 'WCR1_PF', 'POINT007', 1),
('ALIAS008', 'BLD001', 'MQTT_FREEZE_V1', 'TOWER1_TCWin', 'POINT008', 1),
('ALIAS009', 'BLD001', 'MQTT_FREEZE_V1', 'TOWER1_TCWout', 'POINT009', 1),
('ALIAS010', 'BLD001', 'MQTT_FREEZE_V1', 'TOWER1_TWB', 'POINT010', 1),
('ALIAS011', 'BLD001', 'MQTT_FREEZE_V1', 'PUMP1_Flow', 'POINT011', 1),
('ALIAS012', 'BLD001', 'MQTT_FREEZE_V1', 'PUMP1_Pout', 'POINT012', 1),
('ALIAS013', 'BLD001', 'MQTT_FREEZE_V1', 'PUMP1_Pin', 'POINT013', 1),
('ALIAS014', 'BLD001', 'MQTT_FREEZE_V1', 'PUMP1_Z', 'POINT014', 1),
('ALIAS015', 'BLD001', 'MQTT_FREEZE_V1', 'PUMP1_Power', 'POINT015', 1),
('ALIAS016', 'BLD001', 'MQTT_FREEZE_V1', 'AHU1_TotalPress', 'POINT016', 1),
('ALIAS017', 'BLD001', 'MQTT_FREEZE_V1', 'AHU1_EtaT', 'POINT017', 1),
('ALIAS018', 'BLD001', 'MQTT_FREEZE_V1', 'DBO_TDB', 'POINT018', 1),
('ALIAS019', 'BLD001', 'MQTT_FREEZE_V1', 'DBO_RH', 'POINT019', 1),
('ALIAS020', 'BLD002', 'MQTT_FREEZE_V1', 'WCR1_TWin', 'POINT020', 1);

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
