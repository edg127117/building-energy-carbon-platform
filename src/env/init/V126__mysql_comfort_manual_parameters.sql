CREATE TABLE biz_comfort_manual_parameter (
    building_id VARCHAR(32) NOT NULL,
    space_id VARCHAR(32) NOT NULL,
    parameter_key VARCHAR(32) NOT NULL,
    parameter_value DECIMAL(6, 3) NOT NULL,
    PRIMARY KEY (building_id, space_id, parameter_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
