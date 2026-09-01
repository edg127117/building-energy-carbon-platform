USE `iot_telemetry`;

CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_energy_period_result` (
    `ts` TIMESTAMP,
    `native_quantity` DOUBLE,
    `tce_value` DOUBLE,
    `coverage_ratio` DOUBLE,
    `native_quantity_decimal` BINARY(48),
    `tce_value_decimal` BINARY(48),
    `coverage_ratio_decimal` BINARY(24),
    `revision` BIGINT,
    `evidence_hash` BINARY(64)
) TAGS (
    `result_key` BINARY(64),
    `building_id` BINARY(32),
    `point_id` BINARY(32),
    `native_unit_code` BINARY(64),
    `tce_unit_code` BINARY(64),
    `result_nature` BINARY(32)
);
