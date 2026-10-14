-- 天气独立读取入口；写操作仍由后端角色、动态职责和建筑范围校验。
USE `iot_platform`;
DROP PROCEDURE IF EXISTS `bec_weather_menu_guard`;
DELIMITER $$
CREATE PROCEDURE `bec_weather_menu_guard`()
BEGIN
    IF (SELECT COUNT(*) FROM sys_menu WHERE path='/operations' AND menu_type='M') <> 1
       OR (SELECT COUNT(*) FROM sys_menu WHERE path='/configuration/ingestion' AND menu_type='M') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='WEATHER_MENU_PARENT_MISSING';
    END IF;
    IF EXISTS (SELECT path FROM sys_menu WHERE path IN ('/operations/weather',
        '/operations/weather/overview','/operations/weather/history','/configuration/ingestion/weather')
        GROUP BY path HAVING COUNT(*) > 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='WEATHER_MENU_PATH_CONFLICT';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_menu child LEFT JOIN sys_menu parent ON parent.id=child.parent_id
        WHERE (child.path='/operations/weather' AND (child.menu_type<>'M' OR parent.path IS NULL OR parent.path<>'/operations'))
           OR (child.path IN ('/operations/weather/overview','/operations/weather/history')
               AND (child.menu_type<>'C' OR parent.path IS NULL OR parent.path<>'/operations/weather'))
           OR (child.path='/configuration/ingestion/weather'
               AND (child.menu_type<>'C' OR parent.path IS NULL OR parent.path<>'/configuration/ingestion'))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='WEATHER_MENU_HIERARCHY_CONFLICT';
    END IF;
END$$
DELIMITER ;
CALL bec_weather_menu_guard();
DROP PROCEDURE bec_weather_menu_guard;

-- 仅首次新增时为天气目录让出位置，保留其余同级目录的相对顺序。
SET @bec_weather_menu_new = (SELECT COUNT(*) = 0 FROM sys_menu WHERE path='/operations/weather');
UPDATE sys_menu sibling JOIN sys_menu root ON sibling.parent_id=root.id
SET sibling.sort_order=sibling.sort_order+1
WHERE root.path='/operations' AND sibling.sort_order>=3 AND @bec_weather_menu_new=1;

INSERT INTO sys_menu (parent_id,menu_name,menu_type,path,icon,visible,status,sort_order)
SELECT id,'天气数据','M','/operations/weather','cloud',1,1,3 FROM sys_menu root
WHERE root.path='/operations' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/operations/weather');
INSERT INTO sys_menu (parent_id,menu_name,menu_type,path,icon,visible,status,sort_order)
SELECT id,'天气概览','C','/operations/weather/overview','cloud',1,1,1 FROM sys_menu parent
WHERE parent.path='/operations/weather' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/operations/weather/overview');
INSERT INTO sys_menu (parent_id,menu_name,menu_type,path,icon,visible,status,sort_order)
SELECT id,'历史天气','C','/operations/weather/history','trend',1,1,2 FROM sys_menu parent
WHERE parent.path='/operations/weather' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/operations/weather/history');
INSERT INTO sys_menu (parent_id,menu_name,menu_type,path,icon,visible,status,sort_order)
SELECT id,'天气数据接入','C','/configuration/ingestion/weather','cloud',1,1,5 FROM sys_menu parent
WHERE parent.path='/configuration/ingestion' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/configuration/ingestion/weather');

INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m
ON m.path IN ('/operations/weather/overview','/operations/weather/history')
WHERE r.status=1 AND r.role_key IN ('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN');
INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.path='/configuration/ingestion/weather'
WHERE r.status=1 AND r.role_key IN ('ENERGY_MANAGER','PLATFORM_ADMIN');
