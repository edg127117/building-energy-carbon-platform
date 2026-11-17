-- 在现有 TDengine 数据库中显式执行；不修改数据库 KEEP 或既有超级表。
CREATE STABLE IF NOT EXISTS st_radar_observation (
    ts TIMESTAMP, received_at BIGINT, device_time BINARY(64),
    event_id BINARY(36), data_hash BINARY(64),
    identity_id BINARY(32), equip_id BINARY(32), building_id BINARY(32), space_id BINARY(32),
    gateway_mac BINARY(12), device_mac BINARY(12), target_count INT, device_seq INT
) TAGS (source_id BINARY(32));

CREATE STABLE IF NOT EXISTS st_door_observation (
    ts TIMESTAMP, received_at BIGINT, device_time BINARY(64),
    event_id BINARY(36), data_hash BINARY(64),
    identity_id BINARY(32), equip_id BINARY(32), building_id BINARY(32), space_id BINARY(32),
    gateway_mac BINARY(12), device_mac BINARY(12), door_open BOOL
) TAGS (source_id BINARY(32));
