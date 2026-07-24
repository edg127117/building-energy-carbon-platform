DELIMITER //

CREATE PROCEDURE IF NOT EXISTS batch_insert_devices()
BEGIN
    DECLARE i INT DEFAULT 1;
    WHILE i <= 10000 DO
        INSERT IGNORE INTO `iot_device` (`device_id`, `device_name`, `device_type`, `location`)
        VALUES (
            CONCAT('meter-', LPAD(i, 4, '0')),
            CONCAT('测试设备-', LPAD(i, 4, '0')),
            1,
            CONCAT('测试区域-', ((i - 1) DIV 1000 + 1))
        );
        SET i = i + 1;
    END WHILE;
END//

DELIMITER ;

CALL batch_insert_devices();
DROP PROCEDURE IF EXISTS batch_insert_devices;
