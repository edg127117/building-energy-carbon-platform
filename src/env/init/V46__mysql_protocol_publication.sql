USE iot_platform;

CREATE TABLE biz_protocol_target (
 target_id varchar(32) PRIMARY KEY,
 target_name varchar(100) NOT NULL,
 output_version varchar(8) NOT NULL,
 allowed_topics_json text NOT NULL,
 key_sha256 char(64) NOT NULL,
 last_seen bigint NOT NULL DEFAULT 0,
 current_sequence bigint NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE biz_protocol_version (
 version_id varchar(32) PRIMARY KEY,
 draft_id varchar(32) NOT NULL,
 draft_revision bigint NOT NULL,
 configuration_json mediumtext NOT NULL,
 entry_json mediumtext DEFAULT NULL,
 product_sha256 char(64) NOT NULL,
 content_sha256 char(64) NOT NULL,
 created_at bigint NOT NULL,
 UNIQUE KEY uk_protocol_frozen_revision(draft_id,draft_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE biz_protocol_migration_archive (
 archive_id varchar(32) PRIMARY KEY,
 archive_json mediumtext NOT NULL,
 content_sha256 char(64) NOT NULL,
 created_at bigint NOT NULL,
 created_by bigint NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE biz_protocol_deployment (
 target_id varchar(32) NOT NULL,
 sequence_no bigint NOT NULL,
 content_sha256 char(64) NOT NULL,
 content_json mediumtext NOT NULL,
 version_ids_json text NOT NULL,
 approval_id varchar(64) NOT NULL,
 load_status varchar(24) NOT NULL,
 error_code varchar(80) DEFAULT NULL,
 created_at bigint NOT NULL,
 loaded_at bigint NOT NULL DEFAULT 0,
 PRIMARY KEY(target_id,sequence_no),
 UNIQUE KEY uk_protocol_deployment_approval(approval_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
