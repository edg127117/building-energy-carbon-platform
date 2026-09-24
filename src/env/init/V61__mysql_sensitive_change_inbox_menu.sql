-- 集中入口对已登录角色开放；我的申请只返回本人数据，审核与执行仍由后端职责校验。
USE `iot_platform`;

INSERT INTO `sys_menu`
(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`icon`,`visible`,`status`,`sort_order`)
SELECT 210,'敏感变更申请','C','/configuration/access/changeRequests',NULL,'document',1,1,4
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_menu` WHERE `path`='/configuration/access/changeRequests'
);

INSERT IGNORE INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.`id`,m.`id` FROM `sys_role` r
JOIN `sys_menu` m ON m.`path`='/configuration/access/changeRequests'
WHERE r.`status`=1;
