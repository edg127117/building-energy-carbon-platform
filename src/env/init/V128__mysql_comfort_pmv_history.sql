CREATE TABLE biz_comfort_pmv_sample (
    building_id VARCHAR(32) NOT NULL,
    space_id VARCHAR(32) NOT NULL,
    temperature_observed_at BIGINT NOT NULL,
    sampled_at BIGINT NOT NULL,
    indoor_celsius DECIMAL(7,3) NOT NULL,
    pmv DECIMAL(4,2) NOT NULL,
    inputs_json TEXT NOT NULL,
    PRIMARY KEY (building_id, space_id, temperature_observed_at),
    KEY idx_comfort_pmv_sample_time (building_id, space_id, sampled_at)
);
