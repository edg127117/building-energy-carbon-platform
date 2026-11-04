-- 每个建筑时段合并一项任务并保存设备周期输入，接收与入队同事务；计算结果仍保留不可变证据。
CREATE TABLE biz_dynamic_electricity_task (
 building_id VARCHAR(32) NOT NULL,
 start_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL,
 requested_by BIGINT NOT NULL,
 source_kind VARCHAR(32) NOT NULL DEFAULT 'DEVICE_INTERVAL',
 grain VARCHAR(8) NOT NULL DEFAULT 'INTERVAL',
 input_json MEDIUMTEXT NOT NULL,
 input_hash CHAR(64) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 lease_token VARCHAR(32),
 lease_until BIGINT NOT NULL DEFAULT 0,
 next_check BIGINT NOT NULL DEFAULT 0,
 metadata_version CHAR(64),
 result_id CHAR(64),
 summary_json MEDIUMTEXT,
 last_error VARCHAR(100),
 updated_at BIGINT NOT NULL,
 PRIMARY KEY (building_id,start_ms,end_ms),
 KEY idx_dynamic_task_due(state,next_check,lease_until),
 KEY idx_dynamic_task_result(result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 原始累计量的版本核对按受影响时段读取，避免建筑级回执头造成跨月失效。
CREATE INDEX idx_dynamic_raw_window ON biz_telemetry_raw_intent(building_id,event_ms);
