DROP TABLE IF EXISTS biz_daikin_monitor_inbox;
DROP TABLE IF EXISTS biz_daikin_monitor_round;
DROP TABLE IF EXISTS biz_daikin_inbox_guard;
CREATE TABLE biz_daikin_inbox_guard (guard_id INT PRIMARY KEY);
INSERT INTO biz_daikin_inbox_guard(guard_id) VALUES (1);
-- 分钟采集按来源记录计划轮次；临时观测箱仅用于跨MySQL/TDengine重试，成功轮次清空载荷。
CREATE TABLE biz_daikin_monitor_round (
  source_id VARCHAR(200) NOT NULL PRIMARY KEY,
  round_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempts INT NOT NULL,
  lease_token CHAR(36) DEFAULT NULL,
  lease_until BIGINT NOT NULL DEFAULT 0,
  next_attempt_at BIGINT NOT NULL,
  completed_at BIGINT DEFAULT NULL,
  error_code VARCHAR(64) DEFAULT NULL,
  covered_devices INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_daikin_monitor_round_source FOREIGN KEY(source_id) REFERENCES biz_daikin_source(source_id)
);
CREATE TABLE biz_daikin_monitor_inbox (
  observation_id CHAR(64) NOT NULL PRIMARY KEY,
  source_id VARCHAR(200) NOT NULL,
  pending_id VARCHAR(32) NOT NULL,
  round_id BIGINT NOT NULL,
  observed_at BIGINT NOT NULL,
  target_json TEXT NOT NULL,
  observation_json MEDIUMTEXT DEFAULT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at BIGINT NOT NULL DEFAULT 0,
  lease_token CHAR(36) DEFAULT NULL,
  lease_until BIGINT NOT NULL DEFAULT 0,
  error_code VARCHAR(64) DEFAULT NULL,
  CONSTRAINT fk_daikin_monitor_inbox_source FOREIGN KEY(source_id) REFERENCES biz_daikin_source(source_id)
);
