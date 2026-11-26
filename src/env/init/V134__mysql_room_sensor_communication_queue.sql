-- 通讯证据随报文观测一次提交，后台按接收顺序批量应用。
CREATE TABLE biz_room_sensor_comm_pending (
    received_at BIGINT NOT NULL,
    evidence_id CHAR(36) NOT NULL,
    identity_id VARCHAR(32) NOT NULL,
    equip_id VARCHAR(32) NOT NULL,
    building_id VARCHAR(32) NOT NULL,
    profile_code VARCHAR(24) NOT NULL,
    PRIMARY KEY (received_at, evidence_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房间传感器待处理通讯证据';
