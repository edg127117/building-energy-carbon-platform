-- 在目标 TDengine 库内由维护账号执行。旧程序写入的既有列和历史不受影响。
ALTER STABLE st_raw_event ADD COLUMN room_scope BINARY(64);
ALTER STABLE st_raw_event ADD COLUMN room_snapshot BINARY(4096);
ALTER STABLE st_raw_event ADD COLUMN room_received_at BIGINT;
ALTER STABLE st_raw_event ADD COLUMN room_door_open BOOL;
