-- 门窗共用 S4 协议。安装对象与位置名称由台账记录，历史绑定保留待确认状态。
ALTER TABLE biz_room_sensor_source
    ADD COLUMN contact_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN contact_location VARCHAR(80) NULL,
    ADD CONSTRAINT ck_room_sensor_contact_installation CHECK (
        (contact_kind IS NULL AND contact_location IS NULL)
        OR (protocol = 'S4_DOOR' AND contact_kind IS NOT NULL AND contact_kind IN ('DOOR', 'WINDOW')
            AND contact_location IS NOT NULL AND CHAR_LENGTH(TRIM(contact_location)) > 0)
    );
