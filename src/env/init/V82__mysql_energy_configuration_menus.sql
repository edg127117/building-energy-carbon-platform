-- 页面注册不等于授权：为本轮配置与分类页面提供真实菜单，业务写权限仍由原职责校验。
-- 按路径查找并分配ID，不占用既有部署的自定义菜单编号。
INSERT INTO sys_menu(parent_id,menu_name,menu_type,path,visible,status,sort_order)
SELECT parent.id,'用能分析','M','/operations/energy',1,1,4
FROM sys_menu parent WHERE parent.path='/operations' AND parent.menu_type='M'
 AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.path='/operations/energy');

INSERT INTO sys_menu(parent_id,menu_name,menu_type,path,visible,status,sort_order)
SELECT parent.id,'分项与能碳配置','C','/operations/energy/energyItems',1,1,1
FROM sys_menu parent WHERE parent.path='/operations/energy' AND parent.menu_type='M'
 AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.path='/operations/energy/energyItems');

INSERT INTO sys_menu(parent_id,menu_name,menu_type,path,visible,status,sort_order)
SELECT parent.id,'区域用能分析','C','/operations/energy/energyZones',1,1,2
FROM sys_menu parent WHERE parent.path='/operations/energy' AND parent.menu_type='M'
 AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.path='/operations/energy/energyZones');

-- 仅授予既有业务角色菜单可见性；不授予后台维护、审核、发布或核算职责。
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT role.id,menu.id FROM sys_role role JOIN sys_menu menu
 ON menu.path IN ('/operations','/operations/energy','/operations/energy/energyItems','/operations/energy/energyZones')
WHERE role.role_key IN ('PLATFORM_ADMIN','ENERGY_MANAGER','BUILDING_OWNER') AND role.status=1;
