INSERT INTO iot_protocol_profile
(profile_id, profile_code, profile_version, source_topic,
 device_identity_type, device_identity_path,
 protocol_version_path, expected_protocol_version,
 timestamp_path, seq_path, enabled)
VALUES
('PROFILE_V1', 'ENERGY_METER', 1, 'device/raw/energy/up',
 'MAC', '/MAC', '/protocol_version', '1.0', '/timestamp', '/seq', 1),
('PROFILE_V2', 'ENERGY_METER', 2, 'device/raw/energy/up',
 'MAC', '/MAC', '/protocol_version', '2.0', '/timestamp', '/seq', 1),
('PROFILE_DISABLED', 'DISABLED', 1, 'device/raw/disabled/up',
 'MAC', '/MAC', NULL, NULL, NULL, NULL, 0);

INSERT INTO iot_protocol_field_mapping
(mapping_id, profile_id, source_path, metric_code, value_type,
 source_unit, target_unit, scale, offset_value, required_flag, enabled, sort_order)
VALUES
('MAP_V1_2', 'PROFILE_V1', '/current_co2', 'CURRENT_CO2', 'DECIMAL',
 'kgCO2', 'kgCO2', 1, 0, 1, 1, 2),
('MAP_V1_1', 'PROFILE_V1', '/current_energy', 'CURRENT_ENERGY', 'DECIMAL',
 'kWh', 'kWh', 1, 0, 1, 1, 1),
('MAP_V2_1', 'PROFILE_V2', '/energy_wh', 'CURRENT_ENERGY', 'DECIMAL',
 'Wh', 'kWh', 0.001, 0, 1, 1, 1);
