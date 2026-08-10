-- Docker entrypoint 的 MySQL 客户端可能默认使用 latin1。
-- 必须在首个中文 DDL/DML 前明确客户端字符集，否则种子中文会被双重编码成乱码。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

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
                                         `role_key` VARCHAR(50) NOT NULL COMMENT '四类正式业务角色标识',
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

INSERT IGNORE INTO `sys_role`
(`role_key`, `role_name`, `data_scope`, `status`) VALUES
('BUILDING_OWNER', '建筑业主', 'BUILDING', 1),
('ENERGY_MANAGER', '能效管理方', 'BUILDING', 1),
('THIRD_PARTY', '对方开发', 'BUILDING', 1),
('PLATFORM_ADMIN', '己方管理', 'ALL', 1);

INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT u.id, r.id
FROM `sys_user` u
JOIN `sys_role` r ON r.role_key='PLATFORM_ADMIN'
WHERE u.username='admin';
