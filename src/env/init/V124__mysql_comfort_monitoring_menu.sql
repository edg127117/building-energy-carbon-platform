INSERT INTO sys_menu
    (parent_id, menu_name, menu_type, path, component, icon, visible, status, sort_order)
SELECT parent.id, '人体舒适度监控', 'C', '/operations/realtime/comfort', NULL, 'dashboard', 1, 1, 3
FROM sys_menu parent
WHERE parent.path = '/operations/realtime' AND parent.menu_type = 'M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.path = '/operations/realtime/comfort');

-- 只继承已有暖通监控授权。数据查询仍校验具体建筑和设备范围。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT grant_from.role_id, comfort.id
FROM sys_role_menu grant_from
JOIN sys_menu hvac ON hvac.id = grant_from.menu_id AND hvac.path = '/operations/realtime/hvac' AND hvac.menu_type = 'C'
JOIN sys_menu comfort ON comfort.parent_id = hvac.parent_id
    AND comfort.path = '/operations/realtime/comfort' AND comfort.menu_type = 'C';
