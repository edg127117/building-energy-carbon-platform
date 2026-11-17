-- 房间传感器的可信来源与可恢复投递；不保存坐标或完整原始报文。
CREATE TABLE biz_room_sensor_source (
    source_id VARCHAR(32) NOT NULL PRIMARY KEY,
    identity_id VARCHAR(32) NOT NULL,
    gateway_mac CHAR(12) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    protocol VARCHAR(24) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    freshness_seconds INT NOT NULL DEFAULT 300,
    last_storage_ts BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_room_sensor_identity (identity_id),
    UNIQUE KEY uk_room_sensor_topic_identity (topic, identity_id),
    CONSTRAINT ck_room_sensor_protocol CHECK (protocol IN ('MSR01_RADAR', 'S4_DOOR')),
    CONSTRAINT ck_room_sensor_enabled CHECK (enabled IN (0,1)),
    CONSTRAINT ck_room_sensor_freshness CHECK (freshness_seconds > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房间传感器可信来源，不依据报文房间名建立归属';

CREATE TABLE biz_room_sensor_ingest_pending (
    event_id CHAR(36) NOT NULL PRIMARY KEY,
    source_id VARCHAR(32) NOT NULL,
    storage_ts BIGINT NOT NULL,
    received_at BIGINT NOT NULL,
    observation_json JSON NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_room_sensor_storage (source_id, storage_ts),
    KEY idx_room_sensor_retry (next_attempt_at, received_at),
    KEY idx_room_sensor_expiry (received_at),
    CONSTRAINT fk_room_sensor_pending_source FOREIGN KEY (source_id) REFERENCES biz_room_sensor_source(source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='仅投影后的待投递观测，TDengine核验成功后删除';

-- 身份表历史安装的列排序规则可能与当前数据库默认值不同；按实际父列对齐外键，不改旧表。
SET @room_sensor_identity_charset = (
    SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_device_identity' AND COLUMN_NAME='identity_id'
);
SET @room_sensor_identity_collation = (
    SELECT COLLATION_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_device_identity' AND COLUMN_NAME='identity_id'
);
SET @room_sensor_identity_fk_sql = CONCAT(
    'ALTER TABLE biz_room_sensor_source MODIFY identity_id VARCHAR(32) CHARACTER SET ',
    @room_sensor_identity_charset, ' COLLATE ', @room_sensor_identity_collation,
    ' NOT NULL, ADD CONSTRAINT fk_room_sensor_identity FOREIGN KEY (identity_id) REFERENCES biz_device_identity(identity_id)'
);
PREPARE room_sensor_identity_fk_stmt FROM @room_sensor_identity_fk_sql;
EXECUTE room_sensor_identity_fk_stmt;
DEALLOCATE PREPARE room_sensor_identity_fk_stmt;
