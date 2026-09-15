CREATE TABLE IF NOT EXISTS biz_protocol_draft (
  draft_id VARCHAR(32) PRIMARY KEY,
  revision BIGINT NOT NULL,
  configuration_json CLOB NOT NULL,
  updated_at BIGINT NOT NULL,
  updated_by BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS biz_protocol_target (
 target_id VARCHAR(32) PRIMARY KEY,target_name VARCHAR(100) NOT NULL,output_version VARCHAR(8) NOT NULL,
 allowed_topics_json CLOB NOT NULL,key_sha256 CHAR(64) NOT NULL,last_seen BIGINT DEFAULT 0 NOT NULL,current_sequence BIGINT DEFAULT 0 NOT NULL
);
CREATE TABLE IF NOT EXISTS biz_protocol_version (
 version_id VARCHAR(32) PRIMARY KEY,draft_id VARCHAR(32) NOT NULL,draft_revision BIGINT NOT NULL,
 configuration_json CLOB NOT NULL,entry_json CLOB,product_sha256 CHAR(64) NOT NULL,content_sha256 CHAR(64) NOT NULL,created_at BIGINT NOT NULL,
 UNIQUE(draft_id,draft_revision)
);
CREATE TABLE IF NOT EXISTS biz_protocol_migration_archive (
 archive_id VARCHAR(32) PRIMARY KEY,archive_json CLOB NOT NULL,content_sha256 CHAR(64) NOT NULL,created_at BIGINT NOT NULL,created_by BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS biz_protocol_deployment (
 target_id VARCHAR(32) NOT NULL,sequence_no BIGINT NOT NULL,content_sha256 CHAR(64) NOT NULL,content_json CLOB NOT NULL,
 version_ids_json CLOB NOT NULL,approval_id VARCHAR(64) NOT NULL UNIQUE,load_status VARCHAR(24) NOT NULL,error_code VARCHAR(80),
 created_at BIGINT NOT NULL,loaded_at BIGINT DEFAULT 0 NOT NULL,PRIMARY KEY(target_id,sequence_no)
);
