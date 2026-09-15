CREATE TABLE IF NOT EXISTS biz_protocol_draft (
  draft_id VARCHAR(32) PRIMARY KEY,
  revision BIGINT NOT NULL,
  configuration_json CLOB NOT NULL,
  updated_at BIGINT NOT NULL,
  updated_by BIGINT NOT NULL
);
