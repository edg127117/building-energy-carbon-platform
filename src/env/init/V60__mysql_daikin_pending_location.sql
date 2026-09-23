CREATE TABLE IF NOT EXISTS biz_daikin_pending_location (
    pending_id VARCHAR(32) NOT NULL,
    room_space_id VARCHAR(32) NOT NULL,
    monitor_address VARCHAR(50) NOT NULL,
    asset_reference_code VARCHAR(50) NOT NULL,
    mapped_by BIGINT NOT NULL,
    mapped_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (pending_id),
    KEY idx_daikin_pending_location_room (room_space_id),
    CONSTRAINT fk_daikin_pending_location_directory FOREIGN KEY (pending_id)
        REFERENCES biz_daikin_directory (pending_id),
    CONSTRAINT fk_daikin_pending_location_room FOREIGN KEY (room_space_id)
        REFERENCES biz_space (space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金待接入设备人工核对位置，不创建正式设备台账';
