CREATE TABLE IF NOT EXISTS biz_comfort_temperature_band (
    building_id VARCHAR(32) NOT NULL,
    space_id VARCHAR(32) NOT NULL,
    t_low DECIMAL(5, 2) NOT NULL,
    t_high DECIMAL(5, 2) NOT NULL,
    PRIMARY KEY (building_id, space_id),
    CHECK (t_low < t_high)
);

CREATE TABLE IF NOT EXISTS biz_comfort_manual_parameter (
    building_id VARCHAR(32) NOT NULL,
    space_id VARCHAR(32) NOT NULL,
    parameter_key VARCHAR(32) NOT NULL,
    parameter_value DECIMAL(6, 3) NOT NULL,
    PRIMARY KEY (building_id, space_id, parameter_key)
);

CREATE TABLE IF NOT EXISTS biz_comfort_pmv_sample (
    building_id VARCHAR(32) NOT NULL,
    space_id VARCHAR(32) NOT NULL,
    temperature_observed_at BIGINT NOT NULL,
    sampled_at BIGINT NOT NULL,
    indoor_celsius DECIMAL(7,3) NOT NULL,
    pmv DECIMAL(4,2) NOT NULL,
    inputs_json TEXT NOT NULL,
    PRIMARY KEY (building_id,space_id,temperature_observed_at)
);

CREATE TABLE IF NOT EXISTS biz_comfort_parameter_command (
    actor_id BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_json TEXT NULL,
    PRIMARY KEY (actor_id,idempotency_key)
);

CREATE TABLE IF NOT EXISTS biz_comfort_emission_factor_history (
    building_id VARCHAR(32) NOT NULL,
    space_id VARCHAR(32) NOT NULL,
    effective_at BIGINT NOT NULL,
    factor DECIMAL(6,3),
    PRIMARY KEY (building_id,space_id,effective_at)
);
