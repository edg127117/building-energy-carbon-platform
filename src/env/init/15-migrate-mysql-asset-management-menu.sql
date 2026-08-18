-- 工作包 C 资产管理菜单；手工用于已有 MySQL 卷，重复执行结果不变。
USE `iot_platform`;

INSERT INTO `sys_menu`
(`id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `icon`, `visible`, `status`, `sort_order`)
VALUES
(250, 200, '资产管理',     'M', '/system/assets',    NULL, 'database', 1, 1, 3),
(251, 250, '建筑与空间',   'C', '/system/buildings', NULL, 'home',     1, 1, 1),
(252, 250, '设备与测点',   'C', '/system/devices',   NULL, 'tool',     1, 1, 2)
ON DUPLICATE KEY UPDATE
`parent_id`=VALUES(`parent_id`), `menu_name`=VALUES(`menu_name`),
`menu_type`=VALUES(`menu_type`), `path`=VALUES(`path`), `component`=VALUES(`component`),
`icon`=VALUES(`icon`), `visible`=VALUES(`visible`), `status`=VALUES(`status`),
`sort_order`=VALUES(`sort_order`);

-- 页面首阶段仅供平台管理员使用；不扩展建筑业主、能效管理员或第三方菜单。
DELETE rm FROM `sys_role_menu` rm
JOIN `sys_role` r ON r.`id`=rm.`role_id`
WHERE rm.`menu_id` IN (250,251,252) AND r.`role_key`<>'PLATFORM_ADMIN';

INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id` FROM `sys_role` r
JOIN `sys_menu` m ON m.`id` IN (200,250,251,252)
WHERE r.`role_key`='PLATFORM_ADMIN';
