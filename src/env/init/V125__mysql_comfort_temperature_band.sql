CREATE TABLE biz_comfort_temperature_band (
    building_id VARCHAR(32) NOT NULL,
    space_id VARCHAR(32) NOT NULL,
    t_low DECIMAL(5, 2) NOT NULL,
    t_high DECIMAL(5, 2) NOT NULL,
    PRIMARY KEY (building_id, space_id),
    CONSTRAINT chk_comfort_temperature_band CHECK (t_low < t_high)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
