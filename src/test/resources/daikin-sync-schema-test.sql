DROP TABLE IF EXISTS biz_daikin_directory_sync_job;

CREATE TABLE biz_daikin_directory_sync_job (
  job_id CHAR(32) PRIMARY KEY,
  source_id VARCHAR(200) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL,
  fence_token BIGINT NOT NULL,
  lease_token CHAR(36),
  lease_until TIMESTAMP(3),
  next_attempt_at TIMESTAMP(3) NOT NULL,
  error_code VARCHAR(64),
  requested_by BIGINT,
  create_time TIMESTAMP(3) NOT NULL,
  update_time TIMESTAMP(3) NOT NULL,
  completed_at TIMESTAMP(3),
  CONSTRAINT fk_daikin_sync_job_source_test FOREIGN KEY (source_id)
    REFERENCES biz_daikin_source(source_id),
  CONSTRAINT chk_daikin_sync_job_status_test CHECK
    (status IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED'))
);
