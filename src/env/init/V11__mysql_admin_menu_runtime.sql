-- HVAC/管理菜单对齐；由 Flyway 在新库和受控升级库中仅执行一次。
USE `iot_platform`;

INSERT INTO `sys_menu`
(`id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `icon`, `visible`, `status`, `sort_order`)
VALUES
(100, 0,   '中央空调调适',  'M', '/hvac',                   NULL, 'dashboard', 1, 1, 1),
(101, 100, 'HVAC 能效大屏', 'C', '/hvac-demo',              NULL, 'dashboard', 1, 1, 1),
(200, 0,   '系统管理',      'M', '/system',                 NULL, 'setting',   1, 1, 2),
(210, 200, '人员与角色',    'M', '/system/identity',        NULL, 'usergroup', 1, 1, 1),
(211, 210, '用户管理',      'C', '/system/users',           NULL, 'user',      1, 1, 1),
(212, 210, '角色权限',      'C', '/system/roles',           NULL, 'team',      1, 1, 2),
(220, 200, '建筑权限',      'M', '/system/access',          NULL, 'home',      1, 1, 2),
(223, 220, '建筑授权',      'C', '/system/building-access', NULL, 'key',       1, 1, 3),
(240, 200, '后台配置',      'M', '/system/config',          NULL, 'code',      1, 1, 4),
(241, 240, '菜单管理',      'C', '/system/menus',           NULL, 'menu',      1, 1, 1)
ON DUPLICATE KEY UPDATE
`parent_id`=VALUES(`parent_id`), `menu_name`=VALUES(`menu_name`),
`menu_type`=VALUES(`menu_type`), `path`=VALUES(`path`), `component`=VALUES(`component`),
`icon`=VALUES(`icon`), `visible`=VALUES(`visible`), `status`=VALUES(`status`),
`sort_order`=VALUES(`sort_order`);

-- 保留尚未接入的配置节点，但同时隐藏和停用，避免被误分配为可执行入口。
UPDATE `sys_menu` SET `visible`=0, `status`=0
WHERE `id` IN (110,120,130,131,132,133,140,141,150,151,160,161,221,222,230,231,232,242);

-- 接口调用方按建筑授权，清理全部历史菜单误配；重复执行结果不变。
UPDATE `sys_role` SET `role_name`='接口调用方', `data_scope`='BUILDING' WHERE `role_key`='THIRD_PARTY';
DELETE rm FROM `sys_role_menu` rm
JOIN `sys_role` r ON r.`id`=rm.`role_id`
WHERE r.`role_key`='THIRD_PARTY';

-- 其他正式角色仅重置本期稳定菜单集合，不触碰未来菜单和用户关系。
DELETE rm FROM `sys_role_menu` rm
JOIN `sys_role` r ON r.`id`=rm.`role_id`
WHERE r.`role_key` IN ('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')
  AND rm.`menu_id` IN (100,101,200,210,211,212,220,223,240,241);

INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id` FROM `sys_role` r
JOIN `sys_menu` m ON m.`id` IN (100,101)
WHERE r.`role_key` IN ('BUILDING_OWNER','ENERGY_MANAGER');

INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id` FROM `sys_role` r
JOIN `sys_menu` m ON m.`id` IN (100,101,200,210,211,212,220,223,240,241)
WHERE r.`role_key`='PLATFORM_ADMIN';
