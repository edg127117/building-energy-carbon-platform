-- 分钟采集按来源记录计划轮次；临时观测箱仅用于跨MySQL/TDengine重试，成功轮次清空载荷。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;
CREATE TABLE IF NOT EXISTS biz_daikin_inbox_guard (
  guard_id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金观测箱全局容量互斥';
INSERT INTO biz_daikin_inbox_guard(guard_id) VALUES (1);
CREATE TABLE IF NOT EXISTS biz_daikin_monitor_round (
  source_id VARCHAR(200) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金来源最近计划采集轮次';
CREATE TABLE IF NOT EXISTS biz_daikin_monitor_inbox (
  observation_id CHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  source_id VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
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
  KEY idx_daikin_inbox_due(status,next_attempt_at),
  KEY idx_daikin_inbox_round(source_id,round_id),
  CONSTRAINT fk_daikin_monitor_inbox_source FOREIGN KEY(source_id) REFERENCES biz_daikin_source(source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大金跨存储临时观测与幂等检查点';
