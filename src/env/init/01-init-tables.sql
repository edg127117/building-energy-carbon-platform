-- 切换到自动创建的数据库
USE `iot_platform`;

-- 1. 用户/人员表
CREATE TABLE IF NOT EXISTS `sys_user`(
                                         `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                                         `username` VARCHAR(50) NOT NULL COMMENT '登录账号',
    `password` VARCHAR(100) NOT NULL COMMENT '加密密码',
    `nickname` VARCHAR(50) DEFAULT NULL COMMENT '员工姓名/昵称',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `status` TINYINT DEFAULT 1 COMMENT '帐号状态: 0-禁用, 1-正常',
    `del_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常,1-逻辑删除',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '入职/创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(`id`),
    UNIQUE KEY `uk_username` (`username`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户权限与人员管理表';

INSERT INTO `sys_user` (`username`, `password`, `nickname`)
SELECT 'admin', '123456', '超级管理员'
WHERE NOT EXISTS (SELECT 1 FROM `sys_user` WHERE `username`='admin');

-- 1.1 角色表 (RBAC骨架)
CREATE TABLE IF NOT EXISTS `sys_role`(
                                         `id` BIGINT NOT NULL AUTO_INCREMENT,
                                         `role_key` VARCHAR(50) NOT NULL COMMENT '角色标识: ADMIN/USER',
    `role_name` VARCHAR(50) NOT NULL COMMENT '角色名称',
    `data_scope` VARCHAR(16) DEFAULT 'ALL' COMMENT 'ALL-全部,BUILDING-按建筑',
    `status` TINYINT DEFAULT 1 COMMENT '0-禁用,1-正常',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(`id`),
    UNIQUE KEY `uk_role_key` (`role_key`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

CREATE TABLE IF NOT EXISTS `sys_user_role`(
                                              `id` BIGINT NOT NULL AUTO_INCREMENT,
                                              `user_id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(`id`),
    UNIQUE KEY `uk_user_role` (`user_id`,`role_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_role_id` (`role_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

INSERT INTO `sys_role` (`role_key`, `role_name`, `status`)
SELECT 'ADMIN', '管理员', 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_role` WHERE `role_key`='ADMIN');

INSERT INTO `sys_role` (`role_key`, `role_name`, `status`)
SELECT 'USER', '普通用户', 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_role` WHERE `role_key`='USER');

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT u.id, r.id
FROM `sys_user` u, `sys_role` r
WHERE u.username='admin' AND r.role_key='ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM `sys_user_role` ur WHERE ur.user_id=u.id AND ur.role_id=r.id
  );

-- 2. 物联网设备台账表
CREATE TABLE IF NOT EXISTS `iot_device`(
                                           `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                           `device_id` VARCHAR(64) NOT NULL COMMENT '设备唯一物理编号',
    `device_name` VARCHAR(100) NOT NULL COMMENT '设备名称',
    `device_type` TINYINT NOT NULL COMMENT '能源类型:1-电表,2-水表',
    `location` VARCHAR(255) DEFAULT NULL COMMENT '安装区域/位置',
    `building_id` VARCHAR(32) DEFAULT NULL COMMENT '所属建筑ID',
    `status` TINYINT DEFAULT 0 COMMENT '设备状态:0-离线,1-在线,2-故障',
    `ip_address` VARCHAR(50) DEFAULT NULL COMMENT '终端网络IP',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '接入时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(`id`),
    UNIQUE KEY `uk_device_id` (`device_id`),
    INDEX `idx_type_status` (`device_type`,`status`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备台账表';

CREATE TABLE IF NOT EXISTS `iot_device_status_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `device_id` VARCHAR(64) NOT NULL COMMENT '设备物理编号',
    `status` TINYINT NOT NULL COMMENT '变更后状态：0-离线,1-在线',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '状态变更时间',
    PRIMARY KEY (`id`),
    INDEX `idx_device_status_time` (`device_id`, `create_time`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备上下线轨迹日志';

-- 插入一台测试电表
INSERT INTO `iot_device` (`device_id`, `device_name`, `device_type`, `location`)
SELECT 'meter-001', '1号车间总电表', 1, '1栋配电房'
WHERE NOT EXISTS (SELECT 1 FROM `iot_device` WHERE `device_id`='meter-001');

-- 3. 控制指令表 (略缩版，按你的设计方案)
CREATE TABLE IF NOT EXISTS `control_commands`(
                                                 `id` BIGINT NOT NULL AUTO_INCREMENT,
                                                 `command_id` VARCHAR(64) NOT NULL COMMENT '全局唯一指令ID',
    `device_id` VARCHAR(64) NOT NULL COMMENT '目标设备ID',
    `command_type` TINYINT NOT NULL COMMENT '1-遥控2-遥调',
    `command_value` JSON NOT NULL COMMENT '控制参数字典',
    `status` TINYINT DEFAULT 0 COMMENT '0-待校验1-通过2-已下发',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='控制指令下发追踪表';
