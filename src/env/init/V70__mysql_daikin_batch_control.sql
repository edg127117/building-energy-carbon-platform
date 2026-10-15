-- 批量控制队列与预检快照；不改变既有控制许可或 TEST 配置，不自动清理结果。
CREATE TABLE biz_daikin_control_preview (
 preview_id VARCHAR(32) COLLATE utf8mb4_bin PRIMARY KEY,
 operator_id BIGINT NOT NULL, expires_at BIGINT NOT NULL, payload_json MEDIUMTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_daikin_control_batch (
 batch_id VARCHAR(32) COLLATE utf8mb4_bin PRIMARY KEY,
 operator_id BIGINT NOT NULL, idempotency_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL, action VARCHAR(24) NOT NULL, requested_value VARCHAR(40) NOT NULL,
 created_at BIGINT NOT NULL, trace_id VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_daikin_batch_request(operator_id,idempotency_key),
 KEY idx_daikin_batch_history(operator_id,created_at,batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_daikin_control_batch_item (
 item_id VARCHAR(32) COLLATE utf8mb4_bin PRIMARY KEY,
 batch_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL, equipment_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
 controller_key CHAR(64) COLLATE utf8mb4_bin, target_json TEXT, revision CHAR(64) COLLATE utf8mb4_bin,
 relationship_hash CHAR(64) COLLATE utf8mb4_bin, impacted_targets_json TEXT, status VARCHAR(24) NOT NULL, reason_code VARCHAR(80),
 operation_id VARCHAR(32) COLLATE utf8mb4_bin, observed_value VARCHAR(40), unit_status VARCHAR(24),
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, dispatched_at BIGINT, accepted_at BIGINT, readback_at BIGINT,
 lease_token VARCHAR(32) COLLATE utf8mb4_bin, lease_until BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_daikin_batch_device(batch_id,equipment_id),
 KEY idx_daikin_batch_queue(status,created_at,item_id),
 KEY idx_daikin_batch_controller(controller_key,status),
 CONSTRAINT fk_daikin_batch_item FOREIGN KEY(batch_id) REFERENCES biz_daikin_control_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每个控制器至多一个后台发送者，发送后的隔离仍由既有 controller_slot 管理。
CREATE TABLE biz_daikin_control_batch_lane (
 controller_key CHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
 item_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 lease_token VARCHAR(32) COLLATE utf8mb4_bin NOT NULL, lease_until BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 仅在领取短事务中锁定，跨来源/实例共同限制发送并发；网络调用不持有此锁。
CREATE TABLE biz_daikin_control_batch_gate (gate_id INT PRIMARY KEY) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO biz_daikin_control_batch_gate(gate_id) VALUES (1);
