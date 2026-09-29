-- 重组智慧运维监控与设备管理菜单：激活综合总览/运行总览、实时监控/电力监控与设备管理/监测采集设备，
-- 并将原暖通监测与设备台账的角色菜单授权平滑继承至拆分后的对应叶子菜单。
INSERT INTO `sys_menu`
    (`id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `icon`, `visible`, `status`, `sort_order`)
VALUES
    (305, 300, '综合总览', 'M', '/operations/overview', NULL, 'dashboard', 1, 1, 1),
    (306, 305, '运行总览', 'C', '/operations/overview/running', NULL, 'data-board', 1, 1, 1),
    (310, 300, '实时监控', 'M', '/operations/realtime', NULL, 'dashboard', 1, 1, 2),
    (311, 310, '暖通空调监控', 'C', '/operations/realtime/hvac', NULL, 'dashboard', 1, 1, 1),
    (312, 310, '电力监控', 'C', '/operations/realtime/power', NULL, 'trend', 1, 1, 2),
    (230, 300, '设备管理', 'M', '/operations/devices', NULL, 'tool', 1, 1, 5),
    (252, 230, '用能设备台账', 'C', '/operations/devices/businessDevices', NULL, 'tool', 1, 1, 1),
    (256, 230, '监测采集设备', 'C', '/operations/devices/meters', NULL, 'tool', 1, 1, 2),
    (254, 230, '待接入设备', 'C', '/operations/devices/pendingDevices', NULL, 'link', 1, 1, 3)
ON DUPLICATE KEY UPDATE
    `parent_id` = VALUES(`parent_id`),
    `menu_name` = VALUES(`menu_name`),
    `menu_type` = VALUES(`menu_type`),
    `path` = VALUES(`path`),
    `component` = VALUES(`component`),
    `icon` = VALUES(`icon`),
    `visible` = VALUES(`visible`),
    `status` = VALUES(`status`),
    `sort_order` = VALUES(`sort_order`);

-- 原拥有暖通空调监控（311）授权的角色，平滑继承运行总览（306）与电力监控（312）访问权
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT rm.`role_id`, target.`id`
FROM `sys_role_menu` rm
JOIN `sys_menu` src ON src.`id` = rm.`menu_id` AND src.`path` = '/operations/realtime/hvac'
JOIN `sys_menu` target ON target.`path` IN ('/operations/overview/running', '/operations/realtime/power');

-- 原拥有设备台账（252）授权的角色，平滑继承监测采集设备（256）与电力监控（312）访问权
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT rm.`role_id`, target.`id`
FROM `sys_role_menu` rm
JOIN `sys_menu` src ON src.`id` = rm.`menu_id` AND src.`path` = '/operations/devices/businessDevices'
JOIN `sys_menu` target ON target.`path` IN ('/operations/devices/meters', '/operations/realtime/power');
