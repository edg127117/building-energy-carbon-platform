-- 区间输入证据与当前任务分离；JSON保留成对十进制、来源及采用结果，不改D01原始事实与审核账。
CREATE TABLE biz_dynamic_interval_evidence (
 event_id CHAR(64) NOT NULL,
 building_id VARCHAR(32) NOT NULL,
 point_id VARCHAR(80) NOT NULL,
 start_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL,
 version_no BIGINT NOT NULL,
 task_revision BIGINT NOT NULL,
 outcome VARCHAR(24) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 snapshot_json MEDIUMTEXT NOT NULL,
 snapshot_hash CHAR(64) NOT NULL,
 created_ms BIGINT NOT NULL,
 PRIMARY KEY (event_id),
 KEY idx_dynamic_interval_version(building_id,point_id,start_ms,end_ms,version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
