-- 工作包 D 产品模板与设备接入菜单；由 Flyway 在新库和受控升级库中仅执行一次。
USE `iot_platform`;

DROP PROCEDURE IF EXISTS `validate_device_onboarding_menu`;
DELIMITER //
CREATE PROCEDURE `validate_device_onboarding_menu`()
BEGIN
    IF EXISTS (
        SELECT 1 FROM `sys_menu`
        WHERE (`id`=253 AND NOT (`path` <=> '/system/device-products'))
           OR (`id`=254 AND NOT (`path` <=> '/system/device-onboarding'))
           OR (`path`='/system/device-products' AND `id`<>253)
           OR (`path`='/system/device-onboarding' AND `id`<>254)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='DEVICE_ONBOARDING_MENU_ID_OR_PATH_CONFLICT';
    END IF;
END//
DELIMITER ;

CALL `validate_device_onboarding_menu`();
DROP PROCEDURE `validate_device_onboarding_menu`;

INSERT INTO `sys_menu`
(`id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `icon`, `visible`, `status`, `sort_order`)
VALUES
(253, 250, '产品与测点模板', 'C', '/system/device-products',   NULL, 'package-search', 1, 1, 3),
(254, 250, '待绑定设备接入', 'C', '/system/device-onboarding', NULL, 'list-plus',      1, 1, 4)
ON DUPLICATE KEY UPDATE
`parent_id`=VALUES(`parent_id`), `menu_name`=VALUES(`menu_name`),
`menu_type`=VALUES(`menu_type`), `path`=VALUES(`path`), `component`=VALUES(`component`),
`icon`=VALUES(`icon`), `visible`=VALUES(`visible`), `status`=VALUES(`status`),
`sort_order`=VALUES(`sort_order`);

-- 首阶段仍只允许平台管理员看到入口；数据库菜单不替代后端鉴权。
DELETE rm FROM `sys_role_menu` rm
JOIN `sys_role` r ON r.`id`=rm.`role_id`
WHERE rm.`menu_id` IN (253,254) AND r.`role_key`<>'PLATFORM_ADMIN';

INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id` FROM `sys_role` r
JOIN `sys_menu` m ON m.`id` IN (200,250,253,254)
WHERE r.`role_key`='PLATFORM_ADMIN';
