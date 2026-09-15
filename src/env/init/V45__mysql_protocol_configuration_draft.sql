-- 协议草稿只保存配置；原始样例、设备身份值与发布状态不写入此表。
USE `iot_platform`;
CREATE TABLE `biz_protocol_draft` (
  `draft_id` varchar(32) NOT NULL,
  `revision` bigint NOT NULL,
  `configuration_json` mediumtext NOT NULL,
  `updated_at` bigint NOT NULL COMMENT 'Unix epoch milliseconds',
  `updated_by` bigint NOT NULL,
  PRIMARY KEY (`draft_id`),
  KEY `idx_protocol_draft_updated` (`updated_at`,`draft_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='协议可视化配置草稿，不代表云端生效';

-- 新叶子沿用设备接入目录，仅授权平台管理员；后端仍独立校验角色。
INSERT INTO `sys_menu`
(`id`,`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`icon`,`visible`,`status`,`sort_order`)
VALUES (255,250,'协议配置与预览','C','/configuration/ingestion/protocols',NULL,'file-code',1,1,5);

INSERT IGNORE INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.`id`,m.`id` FROM `sys_role` r JOIN `sys_menu` m ON m.`id` IN (200,250,255)
WHERE r.`role_key`='PLATFORM_ADMIN';
