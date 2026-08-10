-- ============================================================================
-- 03-init-hvac-schema.sql — 中央空调调适平台 HVAC 业务表 DDL
-- 来源：空调数据库设计书（第二版） + 设计冻结书 V1.0
-- 策略：纯增量，不删不改旧表
-- ============================================================================
-- Docker entrypoint 会为每份初始化脚本建立执行上下文，不能依赖上一份脚本的会话状态。
-- 在本脚本首个中文 DDL/DML 前再次声明 UTF-8，避免建筑和测点名称被按 latin1 读取。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `iot_platform`;

-- ============================================================================
-- 1. 建筑信息表（building）
-- 设计书 §1.1，本期启用
-- ============================================================================
CREATE TABLE IF NOT EXISTS `building` (
                                                  `building_id`       VARCHAR(32)    NOT NULL COMMENT '建筑ID，主键，雪花算法生成',
    `building_name`     VARCHAR(100)   NOT NULL COMMENT '建筑主体全称',
    `building_code`     VARCHAR(50)    DEFAULT NULL COMMENT '不动产登记号或项目编号',
    `building_type`     VARCHAR(50)    NOT NULL COMMENT '办公/商业/教育/医疗/文化体育/综合',
    `construction_year` INT            DEFAULT NULL COMMENT '竣工年份，用于设备老化程度基准线分析',
    `total_gfa`         DECIMAL(12,2)  NOT NULL COMMENT '总建筑面积(m²)，地上+地下',
    `above_ground_gfa`  DECIMAL(12,2)  DEFAULT NULL COMMENT '地上建筑面积(m²)',
    `underground_gfa`   DECIMAL(12,2)  DEFAULT NULL COMMENT '地下建筑面积(m²)',
    `climate_zone`      VARCHAR(50)    NOT NULL COMMENT '气候区：严寒/寒冷/夏热冬冷/夏热冬暖/温和',
    `design_occupancy`  INT            DEFAULT NULL COMMENT '设计人数，用于人均指标',
    `operating_hours`   VARCHAR(100)   DEFAULT NULL COMMENT '运营时间，如 08:00-18:00 工作日',
    `occupancy_schedule` JSON          DEFAULT NULL COMMENT '占用时间表，按周/季节定义',
    `bems_system`       VARCHAR(100)   DEFAULT NULL COMMENT '现有弱电/群控系统标识（如 Honeywell）',
    `bems_protocol`     VARCHAR(50)    DEFAULT NULL COMMENT 'BEMS通讯协议：BACnet/Modbus/OPC UA/其他',
    `region_code`       VARCHAR(12)    NOT NULL COMMENT '6位国标行政区划代码，关联排放因子',
    `latitude`          DECIMAL(10,6)  DEFAULT NULL COMMENT '纬度',
    `longitude`         DECIMAL(10,6)  DEFAULT NULL COMMENT '经度',
    `create_time`       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`          TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-正常, 1-已删除',
    PRIMARY KEY (`building_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建筑信息表';

-- 试点建筑初始数据
INSERT INTO `building` (`building_id`, `building_name`, `building_code`, `building_type`, `total_gfa`, `climate_zone`, `region_code`)
VALUES ('BLD001', '试点大楼', 'PILOT-001', '办公', 15000.00, '夏热冬冷', '330100');

-- ============================================================================
-- 2. 工业资产身份与命名字典
-- 内部ID负责稳定关联；业务编码仅在建筑范围内唯一。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `biz_space` (
    `space_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `parent_space_id` VARCHAR(32) DEFAULT NULL COMMENT '顶级空间必须为NULL',
    `space_name` VARCHAR(100) NOT NULL,
    `space_code` VARCHAR(50) DEFAULT NULL,
    `space_type` VARCHAR(50) NOT NULL,
    `floor_level` INT DEFAULT NULL,
    `usable_area` DECIMAL(12,2) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `create_by` VARCHAR(32) DEFAULT NULL,
    `update_by` VARCHAR(32) DEFAULT NULL,
    `del_flag` TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`space_id`),
    UNIQUE KEY `uk_space_building_code` (`building_id`, `space_code`),
    UNIQUE KEY `uk_space_id_building` (`space_id`, `building_id`),
    CONSTRAINT `fk_space_building` FOREIGN KEY (`building_id`) REFERENCES `building` (`building_id`),
    CONSTRAINT `fk_space_parent_building` FOREIGN KEY (`parent_space_id`, `building_id`)
        REFERENCES `biz_space` (`space_id`, `building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建筑内部空间';

CREATE TABLE IF NOT EXISTS `biz_system_group` (
    `system_group_id` VARCHAR(32) NOT NULL COMMENT '平台内部全局ID',
    `system_group_code` VARCHAR(50) NOT NULL COMMENT '建筑内业务编码',
    `building_id` VARCHAR(32) NOT NULL,
    `system_type` VARCHAR(100) NOT NULL,
    `system_group_name` VARCHAR(100) NOT NULL,
    `group_desc` VARCHAR(500) DEFAULT NULL,
    `design_cop` DECIMAL(8,2) DEFAULT NULL,
    `design_capacity` DECIMAL(12,2) DEFAULT NULL,
    `annual_budget` DECIMAL(14,2) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `del_flag` TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`system_group_id`),
    UNIQUE KEY `uk_group_building_code` (`building_id`, `system_group_code`),
    UNIQUE KEY `uk_group_id_building` (`system_group_id`, `building_id`),
    CONSTRAINT `fk_group_building` FOREIGN KEY (`building_id`) REFERENCES `building` (`building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用能系统实例';

CREATE TABLE IF NOT EXISTS `biz_equipment_type` (
    `type_code` VARCHAR(20) NOT NULL,
    `type_name` VARCHAR(100) NOT NULL,
    `asset_code_prefix` VARCHAR(20) NOT NULL COMMENT '建筑内自动编号前缀',
    `equip_category` VARCHAR(50) NOT NULL,
    `standard_source` VARCHAR(32) NOT NULL,
    `status` TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (`type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备类型字典';

CREATE TABLE IF NOT EXISTS `biz_point_naming_rule` (
    `rule_id` VARCHAR(32) NOT NULL,
    `standard_version` VARCHAR(32) NOT NULL,
    `family_code` VARCHAR(20) NOT NULL,
    `component_code` VARCHAR(20) NOT NULL,
    `code_template` VARCHAR(100) NOT NULL,
    `standard_source` VARCHAR(32) NOT NULL,
    `status` TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (`rule_id`),
    UNIQUE KEY `uk_point_rule_semantic`
        (`standard_version`, `family_code`, `component_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一测点命名规则';

-- ============================================================================
-- 3. 设备、标准测点、来源别名与指标实例
-- ============================================================================
CREATE TABLE IF NOT EXISTS `biz_equipment` (
    `equip_id` VARCHAR(32) NOT NULL COMMENT '平台内部全局ID',
    `equip_code` VARCHAR(50) NOT NULL COMMENT '建筑内物理资产编码，如WCR1',
    `equip_name` VARCHAR(100) NOT NULL,
    `type_code` VARCHAR(20) NOT NULL,
    `equip_category` VARCHAR(50) NOT NULL,
    `system_group_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `space_id` VARCHAR(32) NOT NULL,
    `manufacturer` VARCHAR(100) DEFAULT NULL,
    `rated_capacity` DECIMAL(12,4) DEFAULT NULL,
    `rated_power` DECIMAL(12,4) DEFAULT NULL,
    `design_cop` DECIMAL(10,4) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `del_flag` TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`equip_id`),
    UNIQUE KEY `uk_equipment_building_code` (`building_id`, `equip_code`),
    UNIQUE KEY `uk_equipment_id_building` (`equip_id`, `building_id`),
    KEY `idx_equipment_type` (`type_code`),
    CONSTRAINT `fk_equipment_type` FOREIGN KEY (`type_code`) REFERENCES `biz_equipment_type` (`type_code`),
    CONSTRAINT `fk_equipment_group_building` FOREIGN KEY (`system_group_id`, `building_id`)
        REFERENCES `biz_system_group` (`system_group_id`, `building_id`),
    CONSTRAINT `fk_equipment_space_building` FOREIGN KEY (`space_id`, `building_id`)
        REFERENCES `biz_space` (`space_id`, `building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备台账';

CREATE TABLE IF NOT EXISTS `biz_data_point` (
    `point_id` VARCHAR(32) NOT NULL COMMENT '平台内部全局ID',
    `point_code` VARCHAR(100) NOT NULL COMMENT '平台标准测点编码',
    `point_name` VARCHAR(100) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `system_group_id` VARCHAR(32) DEFAULT NULL,
    `equip_id` VARCHAR(32) DEFAULT NULL,
    `naming_rule_id` VARCHAR(32) NOT NULL,
    `family_code` VARCHAR(20) NOT NULL,
    `component_code` VARCHAR(20) NOT NULL,
    `suffix_code` VARCHAR(20) NOT NULL,
    `data_type` VARCHAR(20) NOT NULL,
    `unit` VARCHAR(20) DEFAULT NULL,
    `is_for_calc` TINYINT(1) NOT NULL DEFAULT 0,
    `default_value` DECIMAL(12,4) DEFAULT NULL COMMENT '仅保留配置，本阶段不自动补值',
    `value_max` DECIMAL(12,4) DEFAULT NULL,
    `value_min` DECIMAL(12,4) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ONLINE',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `del_flag` TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`point_id`),
    UNIQUE KEY `uk_point_building_code` (`building_id`, `point_code`),
    UNIQUE KEY `uk_point_id_building` (`point_id`, `building_id`),
    CONSTRAINT `fk_point_building` FOREIGN KEY (`building_id`) REFERENCES `building` (`building_id`),
    CONSTRAINT `fk_point_rule` FOREIGN KEY (`naming_rule_id`)
        REFERENCES `biz_point_naming_rule` (`rule_id`),
    CONSTRAINT `fk_point_group_building` FOREIGN KEY (`system_group_id`, `building_id`)
        REFERENCES `biz_system_group` (`system_group_id`, `building_id`),
    CONSTRAINT `fk_point_equipment_building` FOREIGN KEY (`equip_id`, `building_id`)
        REFERENCES `biz_equipment` (`equip_id`, `building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台标准测点';

CREATE TABLE IF NOT EXISTS `biz_point_alias` (
    `alias_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `source_system` VARCHAR(50) NOT NULL,
    `source_point_code` VARCHAR(100) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `status` TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (`alias_id`),
    UNIQUE KEY `uk_alias_source` (`building_id`, `source_system`, `source_point_code`),
    CONSTRAINT `fk_alias_point_building` FOREIGN KEY (`point_id`, `building_id`)
        REFERENCES `biz_data_point` (`point_id`, `building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部测点地址映射';

CREATE TABLE IF NOT EXISTS `biz_indicator` (
    `indicator_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `indicator_code` VARCHAR(100) NOT NULL,
    `scope_type` VARCHAR(20) NOT NULL,
    `scope_id` VARCHAR(32) NOT NULL,
    `equip_id` VARCHAR(32) DEFAULT NULL,
    `system_group_id` VARCHAR(32) DEFAULT NULL,
    `status` TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (`indicator_id`),
    UNIQUE KEY `uk_indicator_scope`
        (`building_id`, `indicator_code`, `scope_type`, `scope_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='性能指标实例';

-- ============================================================================
-- 4. 试点资产与统一命名字典
-- ============================================================================
INSERT INTO `biz_space`
(`space_id`, `building_id`, `parent_space_id`, `space_name`, `space_code`, `space_type`, `floor_level`)
VALUES ('SPACE001', 'BLD001', NULL, '楼顶中央空调机房', 'F-RF-MR01', 'ROOM', 20);

INSERT INTO `biz_system_group`
(`system_group_id`, `system_group_code`, `building_id`, `system_type`, `system_group_name`, `group_desc`)
VALUES ('GROUP001', 'SG001', 'BLD001', 'HVAC', '试点冷热源系统', '主机、水泵、冷却塔和AHU试点链路');

INSERT INTO `biz_equipment_type`
(`type_code`, `type_name`, `asset_code_prefix`, `equip_category`, `standard_source`, `status`)
VALUES
('WCR', '水冷式冷水机组', 'WCR', 'CHILLER', 'HANDOFF', 1),
('WCT', '冷却塔', 'TOWER', 'TOWER', 'FREEZE_EXTENSION', 1),
('WCP', '冷冻水泵', 'PUMP', 'PUMP', 'FREEZE_EXTENSION', 1),
('AHU', '空气处理机组', 'AHU', 'AHU', 'FREEZE_EXTENSION', 1),
('Bh', '热水锅炉', 'Bh', 'BOILER', 'HANDOFF', 1),
('Bs', '蒸汽锅炉', 'Bs', 'BOILER', 'HANDOFF', 1);

INSERT INTO `biz_point_naming_rule`
(`rule_id`, `standard_version`, `family_code`, `component_code`, `code_template`, `standard_source`, `status`)
VALUES
('RULE_WCR_MAIN', 'HANDOFF_V1', 'WCR', 'MAIN', 'WCR[n]', 'HANDOFF', 1),
('RULE_WCR_PC', 'HANDOFF_V1', 'WCR', 'Pc', 'WCR[n]_Pc', 'HANDOFF', 1),
('RULE_WCR_CT', 'HANDOFF_V1', 'WCR', 'CT', 'WCR[n]_CT', 'HANDOFF', 1),
('RULE_WCR_PCD', 'HANDOFF_V1', 'WCR', 'Pcd', 'WCR[n]_Pcd', 'HANDOFF', 1),
('RULE_AHU_MAIN', 'FREEZE_V1', 'AHU', 'MAIN', 'AHU[n]', 'FREEZE_EXTENSION', 1),
('RULE_DBO_ENV', 'HANDOFF_V1', 'DBO', 'ENV', 'DBO', 'HANDOFF', 1),
('RULE_RHO_ENV', 'HANDOFF_V1', 'RHO', 'ENV', 'RHO', 'HANDOFF', 1);

INSERT INTO `biz_equipment`
(`equip_id`, `equip_code`, `equip_name`, `type_code`, `equip_category`,
 `system_group_id`, `building_id`, `space_id`, `rated_capacity`, `rated_power`)
VALUES
('EQUIP_WCR_B1', 'WCR1', '1号水冷冷水机组', 'WCR', 'CHILLER', 'GROUP001', 'BLD001', 'SPACE001', 350.00, 60.00),
('EQUIP_TOWER_B1', 'TOWER1', '1号冷却塔', 'WCT', 'TOWER', 'GROUP001', 'BLD001', 'SPACE001', 500.00, 15.00),
('EQUIP_PUMP_B1', 'PUMP1', '1号冷冻水泵', 'WCP', 'PUMP', 'GROUP001', 'BLD001', 'SPACE001', 37.00, 37.00),
('EQUIP_AHU_B1', 'AHU1', '1号空气处理机组', 'AHU', 'AHU', 'GROUP001', 'BLD001', 'SPACE001', 200.00, 5.50);

-- ============================================================================
-- 5. 19个标准测点与冻结协议别名
-- ============================================================================
INSERT INTO `biz_data_point`
(`point_id`, `point_code`, `point_name`, `building_id`, `system_group_id`, `equip_id`,
 `naming_rule_id`, `family_code`, `component_code`, `suffix_code`,
 `data_type`, `unit`, `is_for_calc`)
VALUES
('POINT001','WCR1_TWin','1号机组冷冻水进水温度','BLD001','GROUP001','EQUIP_WCR_B1','RULE_WCR_MAIN','WCR','MAIN','TWin','ANALOG','℃',1),
('POINT002','WCR1_TWout','1号机组冷冻水出水温度','BLD001','GROUP001','EQUIP_WCR_B1','RULE_WCR_MAIN','WCR','MAIN','TWout','ANALOG','℃',1),
('POINT003','WCR1_GW','1号机组冷冻水流量','BLD001','GROUP001','EQUIP_WCR_B1','RULE_WCR_MAIN','WCR','MAIN','GW','ANALOG','m³/h',1),
('POINT004','WCR1_PPE','1号机组瞬时功率','BLD001','GROUP001','EQUIP_WCR_B1','RULE_WCR_MAIN','WCR','MAIN','PPE','ANALOG','kW',1),
('POINT005','WCR1_Voltage','1号机组压缩机电压','BLD001','GROUP001','EQUIP_WCR_B1','RULE_WCR_MAIN','WCR','MAIN','Voltage','ANALOG','V',1),
('POINT006','WCR1_Current','1号机组压缩机电流','BLD001','GROUP001','EQUIP_WCR_B1','RULE_WCR_MAIN','WCR','MAIN','Current','ANALOG','A',1),
('POINT007','WCR1_PF','1号机组功率因数','BLD001','GROUP001','EQUIP_WCR_B1','RULE_WCR_MAIN','WCR','MAIN','PF','ANALOG','1',1),
('POINT008','WCR1_CT_TWin','1号冷却塔冷却水进水温度','BLD001','GROUP001','EQUIP_TOWER_B1','RULE_WCR_CT','WCR','CT','TWin','ANALOG','℃',1),
('POINT009','WCR1_CT_TWout','1号冷却塔冷却水出水温度','BLD001','GROUP001','EQUIP_TOWER_B1','RULE_WCR_CT','WCR','CT','TWout','ANALOG','℃',1),
('POINT010','WCR1_CT_TWB','1号冷却塔室外湿球温度','BLD001','GROUP001','EQUIP_TOWER_B1','RULE_WCR_CT','WCR','CT','TWB','ANALOG','℃',1),
('POINT011','WCR1_Pc_GW','1号冷冻水泵流量','BLD001','GROUP001','EQUIP_PUMP_B1','RULE_WCR_PC','WCR','Pc','GW','ANALOG','m³/h',1),
('POINT012','WCR1_Pc_Pout','1号冷冻水泵出口压力','BLD001','GROUP001','EQUIP_PUMP_B1','RULE_WCR_PC','WCR','Pc','Pout','ANALOG','Pa',1),
('POINT013','WCR1_Pc_Pin','1号冷冻水泵入口压力','BLD001','GROUP001','EQUIP_PUMP_B1','RULE_WCR_PC','WCR','Pc','Pin','ANALOG','Pa',1),
('POINT014','WCR1_Pc_Z','1号冷冻水泵压力表高度差','BLD001','GROUP001','EQUIP_PUMP_B1','RULE_WCR_PC','WCR','Pc','Z','ANALOG','m',1),
('POINT015','WCR1_Pc_PPE','1号冷冻水泵输入功率','BLD001','GROUP001','EQUIP_PUMP_B1','RULE_WCR_PC','WCR','Pc','PPE','ANALOG','kW',1),
('POINT016','AHU1_TotalPress','1号AHU风机全压值','BLD001','GROUP001','EQUIP_AHU_B1','RULE_AHU_MAIN','AHU','MAIN','TotalPress','ANALOG','Pa',1),
('POINT017','AHU1_EtaT','1号AHU风机总效率','BLD001','GROUP001','EQUIP_AHU_B1','RULE_AHU_MAIN','AHU','MAIN','EtaT','ANALOG','%',1),
('POINT018','DBO','室外干球温度','BLD001',NULL,NULL,'RULE_DBO_ENV','DBO','ENV','TDB','ANALOG','℃',1),
('POINT019','RHO','室外相对湿度','BLD001',NULL,NULL,'RULE_RHO_ENV','RHO','ENV','RH','ANALOG','%',1);

INSERT INTO `biz_point_alias`
(`alias_id`, `building_id`, `source_system`, `source_point_code`, `point_id`, `status`)
VALUES
('ALIAS001','BLD001','MQTT_FREEZE_V1','WCR1_TWin','POINT001',1),
('ALIAS002','BLD001','MQTT_FREEZE_V1','WCR1_TWout','POINT002',1),
('ALIAS003','BLD001','MQTT_FREEZE_V1','WCR1_Flow','POINT003',1),
('ALIAS004','BLD001','MQTT_FREEZE_V1','WCR1_PPE','POINT004',1),
('ALIAS005','BLD001','MQTT_FREEZE_V1','WCR1_Voltage','POINT005',1),
('ALIAS006','BLD001','MQTT_FREEZE_V1','WCR1_Current','POINT006',1),
('ALIAS007','BLD001','MQTT_FREEZE_V1','WCR1_PF','POINT007',1),
('ALIAS008','BLD001','MQTT_FREEZE_V1','TOWER1_TCWin','POINT008',1),
('ALIAS009','BLD001','MQTT_FREEZE_V1','TOWER1_TCWout','POINT009',1),
('ALIAS010','BLD001','MQTT_FREEZE_V1','TOWER1_TWB','POINT010',1),
('ALIAS011','BLD001','MQTT_FREEZE_V1','PUMP1_Flow','POINT011',1),
('ALIAS012','BLD001','MQTT_FREEZE_V1','PUMP1_Pout','POINT012',1),
('ALIAS013','BLD001','MQTT_FREEZE_V1','PUMP1_Pin','POINT013',1),
('ALIAS014','BLD001','MQTT_FREEZE_V1','PUMP1_Z','POINT014',1),
('ALIAS015','BLD001','MQTT_FREEZE_V1','PUMP1_Power','POINT015',1),
('ALIAS016','BLD001','MQTT_FREEZE_V1','AHU1_TotalPress','POINT016',1),
('ALIAS017','BLD001','MQTT_FREEZE_V1','AHU1_EtaT','POINT017',1),
('ALIAS018','BLD001','MQTT_FREEZE_V1','DBO_TDB','POINT018',1),
('ALIAS019','BLD001','MQTT_FREEZE_V1','DBO_RH','POINT019',1);

INSERT INTO `biz_indicator`
(`indicator_id`, `building_id`, `indicator_code`, `scope_type`, `scope_id`, `equip_id`, `system_group_id`)
VALUES
('INDICATOR_WCR_COP_B1','BLD001','WCR_COP','EQUIPMENT','EQUIP_WCR_B1','EQUIP_WCR_B1','GROUP001'),
('INDICATOR_TOWER_EFF_B1','BLD001','TOWER_EFF','EQUIPMENT','EQUIP_TOWER_B1','EQUIP_TOWER_B1','GROUP001'),
('INDICATOR_PUMP_EFF_B1','BLD001','PUMP_EFF','EQUIPMENT','EQUIP_PUMP_B1','EQUIP_PUMP_B1','GROUP001'),
('INDICATOR_AHU_EFF_B1','BLD001','AHU_POW_EFF','EQUIPMENT','EQUIP_AHU_B1','EQUIP_AHU_B1','GROUP001');

-- ============================================================================
-- 6. 菜单权限表（sys_menu）+ 角色菜单关联表（sys_role_menu）
-- 冻结书 7.1 + 9.2，若依 RBAC 模型
-- ============================================================================
CREATE TABLE IF NOT EXISTS `sys_menu` (
                                          `id`          BIGINT        NOT NULL AUTO_INCREMENT,
                                          `parent_id`   BIGINT        DEFAULT 0 COMMENT '父菜单ID，0=顶级',
                                          `menu_name`   VARCHAR(50)   NOT NULL COMMENT '菜单名称',
    `menu_type`   CHAR(1)       DEFAULT 'M' COMMENT 'M-目录, C-菜单, F-按钮',
    `path`        VARCHAR(200)  DEFAULT NULL COMMENT '前端路由地址',
    `component`   VARCHAR(255)  DEFAULT NULL COMMENT '前端组件路径',
    `perms`       VARCHAR(100)  DEFAULT NULL COMMENT '权限标识（如 system:user:list）',
    `icon`        VARCHAR(100)  DEFAULT NULL COMMENT '图标',
    `visible`     TINYINT       DEFAULT 1 COMMENT '0-隐藏, 1-显示',
    `status`      TINYINT       DEFAULT 1 COMMENT '0-停用, 1-正常',
    `sort_order`  INT           DEFAULT 0,
    `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_parent` (`parent_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单权限表';

CREATE TABLE IF NOT EXISTS `sys_role_menu` (
                                               `id`          BIGINT NOT NULL AUTO_INCREMENT,
                                               `role_id`     BIGINT NOT NULL COMMENT '角色ID，外键→sys_role.id',
                                               `menu_id`     BIGINT NOT NULL COMMENT '菜单ID，外键→sys_menu.id',
                                               PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关联表';

-- 已上线入口保持可见；未来菜单保留数据但隐藏，不能被前端当作可执行组件。
INSERT INTO `sys_menu` (`id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `icon`, `visible`, `status`, `sort_order`) VALUES
                                                                                                                    (100, 0,    '中央空调调适', 'M', '/hvac',          NULL,       'dashboard', 1, 1, 1),
                                                                                                                    (101, 100,  'HVAC 能效大屏','C', '/hvac-demo',     NULL,       'dashboard', 1, 1, 1),
                                                                                                                    (110, 100,  '单机调适',    'M', '/single',        NULL,       'control',   0, 0, 2),
                                                                                                                    (120, 110,  '变风量空调系统','M','/single/vav',    NULL,       'apartment', 0, 0, 1),
-- 冷水机组
                                                                                                                    (130, 120,  '冷水机组',    'M', '/single/vav/chiller', NULL,  'cpu',       0, 0, 1),
                                                                                                                    (131, 130,  '制冷量计算',  'C', '/single/vav/chiller/cooling-capacity', 'commissioning/ChillerCooling', 'function', 0, 0, 1),
                                                                                                                    (132, 130,  'COP 计算',    'C', '/single/vav/chiller/cop',              'commissioning/ChillerCop',     'function', 0, 0, 2),
                                                                                                                    (133, 130,  '吸收式 COP',  'C', '/single/vav/chiller/absorption-cop',   'commissioning/AbsorptionCop',   'lock',     0, 0, 3),
-- 冷却塔
                                                                                                                    (140, 120,  '冷却塔',      'M', '/single/vav/tower',    NULL,  'radar',     0, 0, 2),
                                                                                                                    (141, 140,  '冷却塔效率',  'C', '/single/vav/tower/efficiency', 'commissioning/TowerEfficiency', 'function', 0, 0, 1),
-- 水泵
                                                                                                                    (150, 120,  '水泵',        'M', '/single/vav/pump',     NULL,  'sliders',   0, 0, 3),
                                                                                                                    (151, 150,  '水泵效率',    'C', '/single/vav/pump/efficiency',  'commissioning/PumpEfficiency',   'function', 0, 0, 1),
-- 风系统
                                                                                                                    (160, 120,  '风系统',      'M', '/single/vav/ahu',      NULL,  'wind',      0, 0, 4),
                                                                                                                    (161, 160,  '单位风量耗功值','C','/single/vav/ahu/power-efficiency', 'commissioning/AhuEfficiency', 'function', 0, 0, 1);

-- 一级：系统管理
INSERT INTO `sys_menu` (`id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `icon`, `visible`, `status`, `sort_order`) VALUES
                                                                                                                    (200, 0,    '系统管理',    'M', '/system',       NULL,        'setting',   1, 1, 2),
                                                                                                                    (210, 200,  '人员与角色',  'M', '/system/identity', NULL,      'usergroup', 1, 1, 1),
                                                                                                                    (211, 210,  '用户管理',    'C', '/system/users', NULL,        'user',      1, 1, 1),
                                                                                                                    (212, 210,  '角色权限',    'C', '/system/roles', NULL,        'team',      1, 1, 2),
                                                                                                                    (220, 200,  '建筑权限',    'M', '/system/access', NULL,       'home',      1, 1, 2),
                                                                                                                    (221, 220,  '建筑注册',    'C', '/system/building/list', 'system/BuildingList','home',   0, 0, 1),
                                                                                                                    (222, 220,  '空间管理',    'C', '/system/space/list', 'system/SpaceList',  'block',   0, 0, 2),
                                                                                                                    (223, 220,  '建筑授权',    'C', '/system/building-access', NULL, 'key',     1, 1, 3),
                                                                                                                    (230, 200,  '设备管理',    'M', '/system/equipment', NULL,     'tool',     0, 0, 3),
                                                                                                                    (231, 230,  '设备台账',    'C', '/system/equipment/list', 'system/DeviceList', 'database',0, 0, 1),
                                                                                                                    (232, 230,  '测点管理',    'C', '/system/datapoint/list', 'system/PointList',  'node',    0, 0, 2),
                                                                                                                    (240, 200,  '后台配置',    'M', '/system/config', NULL,        'code',     1, 1, 4),
                                                                                                                    (241, 240,  '菜单管理',    'C', '/system/menus', NULL,         'menu',     1, 1, 1),
                                                                                                                    (242, 240,  '数据建模',    'C', '/system/generator', 'system/Generator',   'code',     0, 0, 2);

-- MySQL 不支持 ADD COLUMN IF NOT EXISTS，使用 information_schema 保护增量升级。
SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='del_flag'),
    'SELECT 1',
    'ALTER TABLE `sys_user` ADD COLUMN `del_flag` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除：0-正常，1-删除'''
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema=DATABASE() AND table_name='sys_role' AND column_name='data_scope'),
    'SELECT 1',
    'ALTER TABLE `sys_role` ADD COLUMN `data_scope` VARCHAR(16) DEFAULT ''ALL'' COMMENT ''数据范围：ALL-全部, BUILDING-按建筑, SELF-仅自己'''
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- 8. 向现有角色表追加四类角色（冻结书 9.1）
-- ============================================================================
INSERT IGNORE INTO `sys_role` (`role_key`, `role_name`, `data_scope`) VALUES
('BUILDING_OWNER', '建筑业主',   'BUILDING'),
('ENERGY_MANAGER', '能效管理方', 'BUILDING'),
('THIRD_PARTY',    '对方开发',   'BUILDING'),
('PLATFORM_ADMIN', '己方管理',   'ALL');

-- 对方开发是按建筑授权的接口账号，不拥有内部后台菜单；重复执行可修正旧库脏数据。
UPDATE `sys_role` SET `data_scope`='BUILDING' WHERE `role_key`='THIRD_PARTY';
DELETE rm FROM `sys_role_menu` rm
JOIN `sys_role` r ON r.`id`=rm.`role_id`
WHERE r.`role_key`='THIRD_PARTY';

-- ============================================================================
-- 9. 后端代码生成器 V1 配置表
-- ============================================================================
CREATE TABLE IF NOT EXISTS `gen_table` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `table_name` VARCHAR(128) NOT NULL,
  `table_comment` VARCHAR(255) DEFAULT NULL,
  `module_name` VARCHAR(64) NOT NULL,
  `business_name` VARCHAR(64) NOT NULL,
  `class_name` VARCHAR(128) NOT NULL,
  `package_name` VARCHAR(255) NOT NULL,
  `id_type` VARCHAR(32) NOT NULL DEFAULT 'INPUT',
  `logic_delete_column` VARCHAR(128) DEFAULT NULL,
  `scope_type` VARCHAR(32) NOT NULL DEFAULT 'NONE',
  `scope_column` VARCHAR(128) DEFAULT NULL,
  `read_roles` VARCHAR(1000) NOT NULL,
  `write_roles` VARCHAR(1000) NOT NULL,
  `generate_mode` VARCHAR(32) NOT NULL DEFAULT 'JAVA_ZIP',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gen_table_name` (`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码生成器表级配置';

CREATE TABLE IF NOT EXISTS `gen_column` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `table_id` BIGINT NOT NULL,
  `column_name` VARCHAR(128) NOT NULL,
  `column_comment` VARCHAR(255) DEFAULT NULL,
  `jdbc_type` VARCHAR(64) NOT NULL,
  `java_type` VARCHAR(128) NOT NULL,
  `java_field` VARCHAR(128) NOT NULL,
  `is_primary_key` TINYINT NOT NULL DEFAULT 0,
  `is_nullable` TINYINT NOT NULL DEFAULT 1,
  `is_logic_delete` TINYINT NOT NULL DEFAULT 0,
  `is_list` TINYINT NOT NULL DEFAULT 1,
  `is_query` TINYINT NOT NULL DEFAULT 0,
  `query_type` VARCHAR(32) NOT NULL DEFAULT 'EQ',
  `is_edit` TINYINT NOT NULL DEFAULT 1,
  `is_required` TINYINT NOT NULL DEFAULT 0,
  `component_type` VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  `sort_order` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gen_table_column` (`table_id`, `column_name`),
  KEY `idx_gen_column_table` (`table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码生成器字段级配置';

-- 用户建筑授权与访问申请
CREATE TABLE IF NOT EXISTS `sys_user_building` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_building` (`user_id`, `building_id`),
    INDEX `idx_user_building_user` (`user_id`),
    INDEX `idx_user_building_building` (`building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户建筑授权';

CREATE TABLE IF NOT EXISTS `sys_building_access_request` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `reason` VARCHAR(500) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    `reviewer_id` BIGINT DEFAULT NULL,
    `review_comment` VARCHAR(500) DEFAULT NULL,
    `review_time` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_access_request_user` (`user_id`),
    INDEX `idx_access_request_status` (`status`),
    INDEX `idx_access_request_building` (`building_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建筑访问申请';

-- 平台管理员拥有全部菜单；建筑业主和能效管理方拥有本期能效菜单
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.id, m.id FROM `sys_role` r CROSS JOIN `sys_menu` m
WHERE r.role_key='PLATFORM_ADMIN';

INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.id, m.id FROM `sys_role` r JOIN `sys_menu` m ON m.id IN
  (100,101)
WHERE r.role_key IN ('BUILDING_OWNER','ENERGY_MANAGER');

-- ============================================================================
-- 数据质量补全：MySQL 只保存审批配置和跨库技术任务，分钟值仍写入 TDengine
-- ============================================================================
CREATE TABLE IF NOT EXISTS `biz_point_typical_value_config` (
    `config_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `typical_value` DECIMAL(12,4) NOT NULL,
    `unit` VARCHAR(20) NOT NULL,
    `source_description` VARCHAR(500) NOT NULL,
    `reason` VARCHAR(500) NOT NULL,
    `valid_from` DATETIME(3) NOT NULL,
    `valid_to` DATETIME(3) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL,
    `version` INT NOT NULL,
    `created_by` BIGINT NOT NULL,
    `submitted_at` DATETIME(3) DEFAULT NULL,
    `reviewer_id` BIGINT DEFAULT NULL,
    `review_comment` VARCHAR(500) DEFAULT NULL,
    `reviewed_at` DATETIME(3) DEFAULT NULL,
    `disabled_by` BIGINT DEFAULT NULL,
    `disabled_reason` VARCHAR(500) DEFAULT NULL,
    `disabled_at` DATETIME(3) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`config_id`),
    UNIQUE KEY `uk_typical_point_version` (`point_id`, `version`),
    KEY `idx_typical_building_status` (`building_id`, `status`),
    KEY `idx_typical_effective`
        (`point_id`, `status`, `valid_from`, `valid_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测点典型值审批版本';

CREATE TABLE IF NOT EXISTS `biz_data_quality_fill_task` (
    `task_id` VARCHAR(32) NOT NULL,
    `idempotency_key` VARCHAR(160) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `start_minute` DATETIME(3) NOT NULL,
    `end_minute` DATETIME(3) NOT NULL,
    `minute_count` INT NOT NULL DEFAULT 0,
    `data_quality` TINYINT NOT NULL,
    `source_type` VARCHAR(30) NOT NULL,
    `algorithm_version` VARCHAR(32) NOT NULL,
    `evidence_json` JSON NOT NULL,
    `typical_config_id` VARCHAR(32) DEFAULT NULL,
    `typical_config_version` INT DEFAULT NULL,
    `apply_status` VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    `applied_count` INT NOT NULL DEFAULT 0,
    `failed_count` INT NOT NULL DEFAULT 0,
    `replaced_count` INT NOT NULL DEFAULT 0,
    `voided_count` INT NOT NULL DEFAULT 0,
    `failed_minutes_json` JSON DEFAULT NULL,
    `retry_count` INT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `generated_at` DATETIME(3) NOT NULL,
    `closed_at` DATETIME(3) DEFAULT NULL,
    `void_by` BIGINT DEFAULT NULL,
    `void_reason` VARCHAR(500) DEFAULT NULL,
    `void_at` DATETIME(3) DEFAULT NULL,
    `supersedes_task_id` VARCHAR(32) DEFAULT NULL,
    `recalc_job_id` VARCHAR(32) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`task_id`),
    CONSTRAINT `chk_fill_quality` CHECK (`data_quality` IN (1, 2)),
    UNIQUE KEY `uk_fill_idempotency` (`idempotency_key`),
    KEY `idx_fill_building_range`
        (`building_id`, `start_minute`, `end_minute`),
    KEY `idx_fill_point_range`
        (`point_id`, `start_minute`, `end_minute`),
    KEY `idx_fill_status_update` (`apply_status`, `update_time`),
    KEY `idx_fill_recalc_job` (`recalc_job_id`, `task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质量补全写入与追溯批次';

-- 人工重算是低频管理批次，不按分钟建 MySQL 明细；混合 Q0/Q1/Q2/缺失只累计汇总。
CREATE TABLE IF NOT EXISTS `biz_data_quality_recalc_job` (
    `job_id` VARCHAR(32) NOT NULL,
    `idempotency_key` VARCHAR(160) NOT NULL,
    `job_type` VARCHAR(30) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `point_ids_json` JSON NOT NULL,
    `from_minute` DATETIME(3) NOT NULL,
    `to_minute` DATETIME(3) NOT NULL,
    `supersedes_task_id` VARCHAR(32) DEFAULT NULL,
    `reason` VARCHAR(500) NOT NULL,
    `operator_id` BIGINT NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `phase` VARCHAR(20) NOT NULL,
    `cursor_minute` DATETIME(3) NOT NULL,
    `void_target_minutes_json` JSON DEFAULT NULL,
    `q0_count` INT NOT NULL DEFAULT 0,
    `q1_count` INT NOT NULL DEFAULT 0,
    `q2_count` INT NOT NULL DEFAULT 0,
    `missing_count` INT NOT NULL DEFAULT 0,
    `voided_count` INT NOT NULL DEFAULT 0,
    `replaced_count` INT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `started_at` DATETIME(3) DEFAULT NULL,
    `finished_at` DATETIME(3) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`job_id`),
    UNIQUE KEY `uk_recalc_idempotency` (`idempotency_key`),
    KEY `idx_recalc_status_cursor` (`status`, `update_time`, `job_id`),
    KEY `idx_recalc_building_range`
        (`building_id`, `status`, `from_minute`, `to_minute`),
    KEY `idx_recalc_supersedes`
        (`supersedes_task_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工数据质量重算批次';
