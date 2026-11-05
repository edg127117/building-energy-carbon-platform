-- 兼容既有完整证据。轻量受理记录不存数值快照，清理正文时保留原摘要和消息指纹。
ALTER TABLE biz_dynamic_interval_evidence MODIFY COLUMN snapshot_json MEDIUMTEXT NULL;
ALTER TABLE biz_dynamic_interval_evidence MODIFY COLUMN snapshot_hash CHAR(64) NULL;
ALTER TABLE biz_dynamic_interval_evidence ADD COLUMN snapshot_state VARCHAR(8) NOT NULL DEFAULT 'FULL';
ALTER TABLE biz_dynamic_interval_evidence ADD COLUMN related_event_id CHAR(64) NULL;
ALTER TABLE biz_dynamic_interval_evidence ADD COLUMN archived_ms BIGINT NULL;
CREATE INDEX idx_dynamic_interval_retention ON biz_dynamic_interval_evidence(snapshot_state,created_ms,event_id);
