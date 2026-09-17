-- 只迁移既有叶子路径；保留菜单ID、角色授权及可见性，前端按运维设备管理分组。
UPDATE sys_menu
SET path='/operations/devices/pendingDevices', menu_name='待接入设备'
WHERE path IN ('/system/device-onboarding','/configuration/ingestion/pendingDevices');
