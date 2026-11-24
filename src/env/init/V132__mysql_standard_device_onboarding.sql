DELIMITER //
CREATE PROCEDURE migrate_standard_device_onboarding()
BEGIN
    IF (SELECT COUNT(*) FROM sys_menu
        WHERE id=254 AND menu_type='C' AND path='/operations/devices/pendingDevices') <> 1
       OR (SELECT COUNT(*) FROM sys_menu
           WHERE id=250 AND parent_id=200 AND menu_type='M' AND path='/configuration/ingestion') <> 1
       OR EXISTS (SELECT 1 FROM sys_menu WHERE path='/configuration/ingestion/pendingDevices') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STANDARD_ONBOARDING_MENU_CONFLICT';
    END IF;

    ALTER TABLE biz_product_point_template
        ADD COLUMN data_type VARCHAR(32) NOT NULL DEFAULT 'ANALOG' AFTER unit;

    -- 保留叶子 ID，原有角色授权会随叶子进入配置 Workspace。
    UPDATE sys_menu
    SET parent_id=250, path='/configuration/ingestion/pendingDevices'
    WHERE id=254;
END//
DELIMITER ;

CALL migrate_standard_device_onboarding();
DROP PROCEDURE migrate_standard_device_onboarding;
