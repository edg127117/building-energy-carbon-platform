CREATE TABLE biz_comfort_parameter_command (
    actor_id BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_json TEXT NULL,
    PRIMARY KEY (actor_id, idempotency_key)
);
