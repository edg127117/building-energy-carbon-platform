DROP TABLE IF EXISTS iot_protocol_field_mapping;
DROP TABLE IF EXISTS iot_protocol_profile;

CREATE TABLE iot_protocol_profile (
    profile_id VARCHAR(32) PRIMARY KEY,
    profile_code VARCHAR(50) NOT NULL,
    profile_version INT NOT NULL,
    source_topic VARCHAR(200) NOT NULL,
    device_identity_type VARCHAR(20) NOT NULL,
    device_identity_path VARCHAR(200) NOT NULL,
    protocol_version_path VARCHAR(200),
    expected_protocol_version VARCHAR(50),
    timestamp_path VARCHAR(200),
    seq_path VARCHAR(200),
    message_id_path VARCHAR(200),
    boot_id_path VARCHAR(200),
    batch_id_path VARCHAR(200),
    retransmitted_at_path VARCHAR(200),
    max_ack_mode VARCHAR(30) NOT NULL DEFAULT 'EVIDENCE_ONLY',
    correlation_policy VARCHAR(40) NOT NULL DEFAULT 'NONE',
    enabled TINYINT NOT NULL
);

CREATE TABLE iot_protocol_field_mapping (
    mapping_id VARCHAR(32) PRIMARY KEY,
    profile_id VARCHAR(32) NOT NULL,
    source_path VARCHAR(200) NOT NULL,
    metric_code VARCHAR(100) NOT NULL,
    value_type VARCHAR(20) NOT NULL,
    source_unit VARCHAR(50) NOT NULL,
    target_unit VARCHAR(50) NOT NULL,
    scale DECIMAL(20,9) NOT NULL,
    offset_value DECIMAL(20,9) NOT NULL,
    required_flag TINYINT NOT NULL,
    enabled TINYINT NOT NULL,
    sort_order INT NOT NULL
);
