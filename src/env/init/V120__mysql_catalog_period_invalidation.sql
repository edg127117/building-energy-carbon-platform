-- 全局目录使用独立通知水位；读取端按生效区间计算目录指纹，发布仍锁定通知行防并发。
INSERT INTO biz_carbon_display_source_revision(building_id,domain_key,scope_key,revision) VALUES ('*','CATALOG','*',1);
DELIMITER //
DROP TRIGGER tr_display_global_energy_item_ai//
CREATE TRIGGER tr_display_global_energy_item_ai AFTER INSERT ON biz_energy_item FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_energy_item_au//
CREATE TRIGGER tr_display_global_energy_item_au AFTER UPDATE ON biz_energy_item FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_energy_item_ad//
CREATE TRIGGER tr_display_global_energy_item_ad AFTER DELETE ON biz_energy_item FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_energy_item_version_ai//
CREATE TRIGGER tr_display_global_energy_item_version_ai AFTER INSERT ON biz_energy_item_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_energy_item_version_au//
CREATE TRIGGER tr_display_global_energy_item_version_au AFTER UPDATE ON biz_energy_item_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_energy_item_version_ad//
CREATE TRIGGER tr_display_global_energy_item_version_ad AFTER DELETE ON biz_energy_item_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_energy_item_scope_ai//
CREATE TRIGGER tr_display_global_energy_item_scope_ai AFTER INSERT ON biz_energy_item_version_scope FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_energy_item_scope_au//
CREATE TRIGGER tr_display_global_energy_item_scope_au AFTER UPDATE ON biz_energy_item_version_scope FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_energy_item_scope_ad//
CREATE TRIGGER tr_display_global_energy_item_scope_ad AFTER DELETE ON biz_energy_item_version_scope FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_measure_unit_ai//
CREATE TRIGGER tr_display_global_measure_unit_ai AFTER INSERT ON biz_measurement_unit FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_measure_unit_au//
CREATE TRIGGER tr_display_global_measure_unit_au AFTER UPDATE ON biz_measurement_unit FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_measure_unit_ad//
CREATE TRIGGER tr_display_global_measure_unit_ad AFTER DELETE ON biz_measurement_unit FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_measure_unit_version_ai//
CREATE TRIGGER tr_display_global_measure_unit_version_ai AFTER INSERT ON biz_measurement_unit_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_measure_unit_version_au//
CREATE TRIGGER tr_display_global_measure_unit_version_au AFTER UPDATE ON biz_measurement_unit_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DROP TRIGGER tr_display_global_measure_unit_version_ad//
CREATE TRIGGER tr_display_global_measure_unit_version_ad AFTER DELETE ON biz_measurement_unit_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','CATALOG','*'); END//
DELIMITER ;
