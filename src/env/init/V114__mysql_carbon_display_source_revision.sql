-- 展示结果读取按来源版本做短事务校验；来源水位随原始写入同事务递增。
CREATE TABLE biz_carbon_display_source_revision (
    building_id VARCHAR(32) NOT NULL,
    domain_key VARCHAR(32) NOT NULL,
    scope_key VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (building_id, domain_key, scope_key),
    CONSTRAINT chk_display_source_revision_nonnegative CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO biz_carbon_display_source_revision(building_id,domain_key,scope_key,revision)
VALUES ('*','DYNAMIC_INPUT','*',0),('*','DYNAMIC_RESULT','*',0),
       ('*','DAILY_INPUT','*',0),('*','DAILY_RESULT','*',0);

DELIMITER //
CREATE PROCEDURE bump_carbon_display_revision(
    IN p_building VARCHAR(32), IN p_domain VARCHAR(32), IN p_scope VARCHAR(16))
BEGIN
    INSERT INTO biz_carbon_display_source_revision(building_id,domain_key,scope_key,revision,updated_at)
    VALUES (p_building,p_domain,p_scope,1,CURRENT_TIMESTAMP(3))
    ON DUPLICATE KEY UPDATE revision=revision+1,updated_at=VALUES(updated_at);
END//

-- epoch毫秒使用UTC月份键；不依赖MySQL会话或宿主机时区。
CREATE PROCEDURE bump_carbon_display_window(
    IN p_building VARCHAR(32), IN p_domain VARCHAR(32), IN p_from_ms BIGINT, IN p_to_ms BIGINT)
BEGIN
    DECLARE v_month DATE;
    DECLARE v_last DATE;
    IF p_from_ms IS NULL OR p_to_ms IS NULL THEN
        CALL bump_carbon_display_revision(p_building,p_domain,'*');
    ELSEIF p_from_ms < p_to_ms THEN
        SET v_month=DATE_ADD('1970-01-01', INTERVAL FLOOR(p_from_ms/86400000) DAY);
        SET v_month=STR_TO_DATE(DATE_FORMAT(v_month,'%Y-%m-01'),'%Y-%m-%d');
        SET v_last=DATE_ADD('1970-01-01', INTERVAL FLOOR((p_to_ms-1)/86400000) DAY);
        SET v_last=STR_TO_DATE(DATE_FORMAT(v_last,'%Y-%m-01'),'%Y-%m-%d');
        WHILE v_month <= v_last DO
            CALL bump_carbon_display_revision(p_building,p_domain,CONCAT('U:',DATE_FORMAT(v_month,'%Y-%m')));
            SET v_month=DATE_ADD(v_month,INTERVAL 1 MONTH);
        END WHILE;
    END IF;
END//

CREATE PROCEDURE bump_carbon_display_local_month(
    IN p_building VARCHAR(32), IN p_domain VARCHAR(32), IN p_date DATE)
BEGIN
    IF p_date IS NULL THEN
        CALL bump_carbon_display_revision(p_building,p_domain,'*');
    ELSE
        CALL bump_carbon_display_revision(p_building,p_domain,CONCAT('M:',DATE_FORMAT(p_date,'%Y-%m')));
    END IF;
END//

CREATE PROCEDURE bump_display_manual_batch_window(IN p_batch VARCHAR(32),IN p_building VARCHAR(32))
BEGIN
    DECLARE v_from BIGINT;
    DECLARE v_to BIGINT;
    SELECT MIN(start_inclusive),MAX(end_exclusive) INTO v_from,v_to
      FROM biz_energy_manual_reading WHERE batch_id=p_batch AND building_id=p_building;
    IF v_from IS NOT NULL AND v_to IS NOT NULL THEN
        CALL bump_carbon_display_window(p_building,'DAILY_INPUT',v_from,v_to);
        CALL bump_carbon_display_window(p_building,'DYNAMIC_INPUT',v_from,v_to);
    ELSE
        CALL bump_carbon_display_revision(p_building,'DAILY_INPUT','*');
        CALL bump_carbon_display_revision(p_building,'DYNAMIC_INPUT','*');
    END IF;
END//
CREATE PROCEDURE bump_display_manual_fact(
    IN p_batch VARCHAR(32),IN p_building VARCHAR(32),IN p_from BIGINT,IN p_to BIGINT)
BEGIN
    DECLARE v_status VARCHAR(24);
    SELECT status INTO v_status FROM biz_energy_manual_batch WHERE batch_id=p_batch;
    IF v_status='APPROVED' THEN
        CALL bump_carbon_display_window(p_building,'DAILY_INPUT',p_from,p_to);
        CALL bump_carbon_display_window(p_building,'DYNAMIC_INPUT',p_from,p_to);
    END IF;
END//

-- 日趋势读取封账快照关联的批次状态；只使该批次涉及的DAY快照月份失效。
CREATE PROCEDURE bump_display_recalculation_batch_window(IN p_batch VARCHAR(32),IN p_building VARCHAR(32))
BEGIN
    DECLARE v_month DATE;
    DECLARE v_last DATE;
    SELECT MIN(DATE(period_start)),MAX(DATE(period_end-INTERVAL 1 MICROSECOND))
      INTO v_month,v_last
      FROM biz_energy_period_result_snapshot
      WHERE CAST(source_batch_id AS BINARY)=CAST(p_batch AS BINARY)
        AND CAST(building_id AS BINARY)=CAST(p_building AS BINARY) AND period_type='DAY';
    IF v_month IS NOT NULL AND v_last IS NOT NULL THEN
        SET v_month=STR_TO_DATE(DATE_FORMAT(v_month,'%Y-%m-01'),'%Y-%m-%d');
        SET v_last=STR_TO_DATE(DATE_FORMAT(v_last,'%Y-%m-01'),'%Y-%m-%d');
        WHILE v_month<=v_last DO
            CALL bump_carbon_display_local_month(p_building,'DAILY_INPUT',v_month);
            SET v_month=DATE_ADD(v_month,INTERVAL 1 MONTH);
        END WHILE;
    END IF;
END//

CREATE TRIGGER tr_display_manual_batch_ai AFTER INSERT ON biz_energy_manual_batch FOR EACH ROW
BEGIN
    IF NEW.status='APPROVED' THEN
        CALL bump_display_manual_batch_window(NEW.batch_id,NEW.building_id);
    END IF;
END//
CREATE TRIGGER tr_display_manual_batch_au AFTER UPDATE ON biz_energy_manual_batch FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.status <=> NEW.status)
       OR NOT(OLD.payload_hash <=> NEW.payload_hash) OR NOT(OLD.revision <=> NEW.revision)
       OR NOT(OLD.reviewed_at <=> NEW.reviewed_at) THEN
        IF OLD.status='APPROVED' THEN
            CALL bump_display_manual_batch_window(OLD.batch_id,OLD.building_id);
        END IF;
        IF NEW.status='APPROVED' THEN
            CALL bump_display_manual_batch_window(NEW.batch_id,NEW.building_id);
        END IF;
    END IF;
END//
CREATE TRIGGER tr_display_manual_batch_ad AFTER DELETE ON biz_energy_manual_batch FOR EACH ROW
BEGIN
    IF OLD.status='APPROVED' THEN
        CALL bump_display_manual_batch_window(OLD.batch_id,OLD.building_id);
    END IF;
END//

CREATE TRIGGER tr_display_manual_fact_ai AFTER INSERT ON biz_energy_manual_reading FOR EACH ROW
BEGIN
    CALL bump_display_manual_fact(NEW.batch_id,NEW.building_id,NEW.start_inclusive,NEW.end_exclusive);
END//
CREATE TRIGGER tr_display_manual_fact_au AFTER UPDATE ON biz_energy_manual_reading FOR EACH ROW
BEGIN
    IF NOT(OLD.batch_id <=> NEW.batch_id) OR NOT(OLD.building_id <=> NEW.building_id)
       OR NOT(OLD.start_inclusive <=> NEW.start_inclusive) OR NOT(OLD.end_exclusive <=> NEW.end_exclusive)
       OR NOT(OLD.reading_json <=> NEW.reading_json) OR NOT(OLD.normalized_quantity <=> NEW.normalized_quantity)
       OR NOT(OLD.supersedes_fact_id <=> NEW.supersedes_fact_id) THEN
        CALL bump_display_manual_fact(OLD.batch_id,OLD.building_id,OLD.start_inclusive,OLD.end_exclusive);
        CALL bump_display_manual_fact(NEW.batch_id,NEW.building_id,NEW.start_inclusive,NEW.end_exclusive);
    END IF;
END//
CREATE TRIGGER tr_display_manual_fact_ad AFTER DELETE ON biz_energy_manual_reading FOR EACH ROW
BEGIN
    CALL bump_display_manual_fact(OLD.batch_id,OLD.building_id,OLD.start_inclusive,OLD.end_exclusive);
END//

CREATE TRIGGER tr_display_point_profile_ai AFTER INSERT ON biz_energy_point_profile FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_point_profile_au AFTER UPDATE ON biz_energy_point_profile FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.config_revision <=> NEW.config_revision)
       OR NOT(OLD.confirmation_status <=> NEW.confirmation_status) OR NOT(OLD.value_semantics <=> NEW.value_semantics)
       OR NOT(OLD.energy_type <=> NEW.energy_type) OR NOT(OLD.energy_subtype <=> NEW.energy_subtype)
       OR NOT(OLD.reporting_period <=> NEW.reporting_period) OR NOT(OLD.annual_summary <=> NEW.annual_summary)
       OR NOT(OLD.evidence_reference <=> NEW.evidence_reference) THEN
        CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*');
        CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*');
    END IF;
END//
CREATE TRIGGER tr_display_point_profile_ad AFTER DELETE ON biz_energy_point_profile FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); END//

CREATE TRIGGER tr_display_item_binding_ai AFTER INSERT ON biz_energy_point_item_binding FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_item_binding_au AFTER UPDATE ON biz_energy_point_item_binding FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.point_id <=> NEW.point_id) THEN
        CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*');
        CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*');
    END IF;
END//
CREATE TRIGGER tr_display_item_binding_ad AFTER DELETE ON biz_energy_point_item_binding FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); END//

CREATE TRIGGER tr_display_item_binding_version_ai AFTER INSERT ON biz_energy_point_item_binding_version FOR EACH ROW
BEGIN
    DECLARE v_building VARCHAR(32);
    SELECT building_id INTO v_building FROM biz_energy_point_item_binding WHERE binding_id=NEW.binding_id;
    IF v_building IS NOT NULL THEN CALL bump_carbon_display_revision(v_building,'DYNAMIC_INPUT','*'); END IF;
END//
CREATE TRIGGER tr_display_item_binding_version_au AFTER UPDATE ON biz_energy_point_item_binding_version FOR EACH ROW
BEGIN
    DECLARE v_building VARCHAR(32);
    IF NOT(OLD.binding_id <=> NEW.binding_id)
       OR NOT(OLD.confirmation_status <=> NEW.confirmation_status) OR NOT(OLD.effective_from <=> NEW.effective_from)
       OR NOT(OLD.effective_to <=> NEW.effective_to) OR NOT(OLD.config_revision <=> NEW.config_revision)
       OR NOT(OLD.energy_item_version_id <=> NEW.energy_item_version_id) THEN
        SELECT building_id INTO v_building FROM biz_energy_point_item_binding WHERE binding_id=OLD.binding_id;
        IF v_building IS NOT NULL THEN CALL bump_carbon_display_revision(v_building,'DYNAMIC_INPUT','*'); END IF;
        SELECT building_id INTO v_building FROM biz_energy_point_item_binding WHERE binding_id=NEW.binding_id;
        IF v_building IS NOT NULL THEN CALL bump_carbon_display_revision(v_building,'DYNAMIC_INPUT','*'); END IF;
    END IF;
END//
CREATE TRIGGER tr_display_item_binding_version_ad AFTER DELETE ON biz_energy_point_item_binding_version FOR EACH ROW
BEGIN
    DECLARE v_building VARCHAR(32);
    SELECT building_id INTO v_building FROM biz_energy_point_item_binding WHERE binding_id=OLD.binding_id;
    IF v_building IS NOT NULL THEN CALL bump_carbon_display_revision(v_building,'DYNAMIC_INPUT','*'); END IF;
END//

CREATE TRIGGER tr_display_raw_intent_ai AFTER INSERT ON biz_telemetry_raw_intent FOR EACH ROW
BEGIN CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_INPUT',NEW.event_ms,NEW.event_ms+1); END//
CREATE TRIGGER tr_display_raw_intent_au AFTER UPDATE ON biz_telemetry_raw_intent FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.event_ms <=> NEW.event_ms)
       OR NOT(OLD.payload_hash <=> NEW.payload_hash) OR NOT(OLD.storage_state <=> NEW.storage_state)
       OR NOT(OLD.conflict_state <=> NEW.conflict_state) OR NOT(OLD.conflict_revision <=> NEW.conflict_revision)
       OR NOT(OLD.selected_json <=> NEW.selected_json) OR NOT(OLD.selection_version <=> NEW.selection_version) THEN
        CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_INPUT',OLD.event_ms,OLD.event_ms+1);
        CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_INPUT',NEW.event_ms,NEW.event_ms+1);
    END IF;
END//
CREATE TRIGGER tr_display_raw_intent_ad AFTER DELETE ON biz_telemetry_raw_intent FOR EACH ROW
BEGIN CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_INPUT',OLD.event_ms,OLD.event_ms+1); END//

CREATE TRIGGER tr_display_manual_factor_ai AFTER INSERT ON biz_dynamic_electricity_factor FOR EACH ROW
BEGIN CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_INPUT',NEW.start_ms,NEW.end_ms); END//
CREATE TRIGGER tr_display_manual_factor_au AFTER UPDATE ON biz_dynamic_electricity_factor FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.start_ms <=> NEW.start_ms)
       OR NOT(OLD.end_ms <=> NEW.end_ms) OR NOT(OLD.payload_hash <=> NEW.payload_hash) THEN
        CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_INPUT',OLD.start_ms,OLD.end_ms);
        CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_INPUT',NEW.start_ms,NEW.end_ms);
    END IF;
END//
CREATE TRIGGER tr_display_manual_factor_ad AFTER DELETE ON biz_dynamic_electricity_factor FOR EACH ROW
BEGIN CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_INPUT',OLD.start_ms,OLD.end_ms); END//

CREATE TRIGGER tr_display_dynamic_task_ai AFTER INSERT ON biz_dynamic_electricity_task FOR EACH ROW
BEGIN
    IF NEW.source_kind='DEVICE_INTERVAL' THEN
        CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_INPUT',NEW.start_ms,NEW.end_ms);
    END IF;
    CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_RESULT',NEW.start_ms,NEW.end_ms);
END//
CREATE TRIGGER tr_display_dynamic_task_au AFTER UPDATE ON biz_dynamic_electricity_task FOR EACH ROW
BEGIN
    IF (OLD.source_kind='DEVICE_INTERVAL' OR NEW.source_kind='DEVICE_INTERVAL')
       AND (NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.start_ms <=> NEW.start_ms) OR NOT(OLD.end_ms <=> NEW.end_ms)
       OR NOT(OLD.input_hash <=> NEW.input_hash) OR NOT(OLD.source_kind <=> NEW.source_kind) OR NOT(OLD.grain <=> NEW.grain)) THEN
        CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_INPUT',OLD.start_ms,OLD.end_ms);
        CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_INPUT',NEW.start_ms,NEW.end_ms);
    END IF;
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.start_ms <=> NEW.start_ms) OR NOT(OLD.end_ms <=> NEW.end_ms)
       OR NOT(OLD.metadata_version <=> NEW.metadata_version) OR NOT(OLD.result_id <=> NEW.result_id)
       OR NOT(OLD.summary_json <=> NEW.summary_json) OR NOT(OLD.source_kind <=> NEW.source_kind) OR NOT(OLD.grain <=> NEW.grain)
       OR ((OLD.source_kind='DEVICE_INTERVAL' OR NEW.source_kind='DEVICE_INTERVAL')
           AND ((OLD.state='READY') <> (NEW.state='READY'))) THEN
        CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_RESULT',OLD.start_ms,OLD.end_ms);
        CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_RESULT',NEW.start_ms,NEW.end_ms);
    END IF;
END//
CREATE TRIGGER tr_display_dynamic_task_ad AFTER DELETE ON biz_dynamic_electricity_task FOR EACH ROW
BEGIN
    IF OLD.source_kind='DEVICE_INTERVAL' THEN
        CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_INPUT',OLD.start_ms,OLD.end_ms);
    END IF;
    CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_RESULT',OLD.start_ms,OLD.end_ms);
END//

CREATE TRIGGER tr_display_dynamic_result_ai AFTER INSERT ON biz_dynamic_electricity_result FOR EACH ROW
BEGIN CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_RESULT',NEW.start_ms,NEW.end_ms); END//
CREATE TRIGGER tr_display_dynamic_result_au AFTER UPDATE ON biz_dynamic_electricity_result FOR EACH ROW
BEGIN
    IF NOT(OLD.result_id <=> NEW.result_id) OR NOT(OLD.grain <=> NEW.grain) OR NOT(OLD.source_version <=> NEW.source_version)
       OR NOT(OLD.payload_hash <=> NEW.payload_hash) OR NOT(OLD.building_id <=> NEW.building_id)
       OR NOT(OLD.start_ms <=> NEW.start_ms) OR NOT(OLD.end_ms <=> NEW.end_ms) THEN
        CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_RESULT',OLD.start_ms,OLD.end_ms);
        CALL bump_carbon_display_window(NEW.building_id,'DYNAMIC_RESULT',NEW.start_ms,NEW.end_ms);
    END IF;
END//
CREATE TRIGGER tr_display_dynamic_result_ad AFTER DELETE ON biz_dynamic_electricity_result FOR EACH ROW
BEGIN CALL bump_carbon_display_window(OLD.building_id,'DYNAMIC_RESULT',OLD.start_ms,OLD.end_ms); END//

-- 配置头不带期间或源版本按建筑全局生效；时间适用范围仍由既有证据复核。
CREATE TRIGGER tr_display_point_ai AFTER INSERT ON biz_data_point FOR EACH ROW
BEGIN
    CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*');
    CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*');
END//
CREATE TRIGGER tr_display_point_au AFTER UPDATE ON biz_data_point FOR EACH ROW
BEGIN
    IF NOT(OLD.point_id <=> NEW.point_id) OR NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.unit <=> NEW.unit) OR NOT(OLD.equip_id <=> NEW.equip_id)
       OR NOT(OLD.del_flag <=> NEW.del_flag) OR NOT(OLD.status <=> NEW.status) THEN
        CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*');
        CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*');
        CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*');
        CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*');
    END IF;
END//
CREATE TRIGGER tr_display_point_ad AFTER DELETE ON biz_data_point FOR EACH ROW
BEGIN
    CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*');
    CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*');
END//

CREATE TRIGGER tr_display_classification_ai AFTER INSERT ON biz_energy_classification_version FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*'); END//
CREATE TRIGGER tr_display_classification_au AFTER UPDATE ON biz_energy_classification_version FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.revision <=> NEW.revision) OR NOT(OLD.status <=> NEW.status) OR NOT(OLD.effective_from <=> NEW.effective_from) OR NOT(OLD.effective_to <=> NEW.effective_to)
       OR NOT(OLD.configuration_json <=> NEW.configuration_json) THEN
        CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*');
        CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*');
    END IF;
END//
CREATE TRIGGER tr_display_classification_ad AFTER DELETE ON biz_energy_classification_version FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*'); END//

CREATE TRIGGER tr_display_relation_model_ai AFTER INSERT ON biz_relation_model FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*'); END//
CREATE TRIGGER tr_display_relation_model_au AFTER UPDATE ON biz_relation_model FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.governance_mode <=> NEW.governance_mode)
       OR NOT(OLD.active_version_id <=> NEW.active_version_id) OR NOT(OLD.config_revision <=> NEW.config_revision) THEN
        CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*');
        CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*');
    END IF;
END//
CREATE TRIGGER tr_display_relation_model_ad AFTER DELETE ON biz_relation_model FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*'); END//

CREATE TRIGGER tr_display_relation_version_ai AFTER INSERT ON biz_relation_version FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*'); END//
CREATE TRIGGER tr_display_relation_version_au AFTER UPDATE ON biz_relation_version FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.config_revision <=> NEW.config_revision) OR NOT(OLD.status <=> NEW.status) OR NOT(OLD.snapshot_sha256 <=> NEW.snapshot_sha256)
       OR NOT(OLD.effective_at <=> NEW.effective_at) OR NOT(OLD.superseded_at <=> NEW.superseded_at) THEN
        CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*');
        CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*');
    END IF;
END//
CREATE TRIGGER tr_display_relation_version_ad AFTER DELETE ON biz_relation_version FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*'); END//

CREATE TRIGGER tr_display_historical_scope_ai AFTER INSERT ON biz_historical_metering_scope FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*'); END//
CREATE TRIGGER tr_display_historical_scope_au AFTER UPDATE ON biz_historical_metering_scope FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.relation_version_id <=> NEW.relation_version_id) OR NOT(OLD.start_epoch_ms <=> NEW.start_epoch_ms)
       OR NOT(OLD.end_epoch_ms <=> NEW.end_epoch_ms) OR NOT(OLD.status <=> NEW.status) OR NOT(OLD.snapshot_sha256 <=> NEW.snapshot_sha256) THEN
        CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*');
        CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*');
    END IF;
END//
CREATE TRIGGER tr_display_historical_scope_ad AFTER DELETE ON biz_historical_metering_scope FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*'); END//

CREATE TRIGGER tr_display_project_ai AFTER INSERT ON biz_carbon_project_version FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*'); END//
CREATE TRIGGER tr_display_project_au AFTER UPDATE ON biz_carbon_project_version FOR EACH ROW
BEGIN
    IF NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.revision <=> NEW.revision) OR NOT(OLD.status <=> NEW.status) OR NOT(OLD.effective_from <=> NEW.effective_from)
       OR NOT(OLD.effective_to <=> NEW.effective_to) OR NOT(OLD.configuration_json <=> NEW.configuration_json) THEN
        CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*');
        CALL bump_carbon_display_revision(NEW.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(NEW.building_id,'DAILY_INPUT','*');
    END IF;
END//
CREATE TRIGGER tr_display_project_ad AFTER DELETE ON biz_carbon_project_version FOR EACH ROW
BEGIN CALL bump_carbon_display_revision(OLD.building_id,'DYNAMIC_INPUT','*'); CALL bump_carbon_display_revision(OLD.building_id,'DAILY_INPUT','*'); END//

-- 能源目录与全局质量策略没有建筑列，使用星号水位覆盖所有依赖建筑。
CREATE TRIGGER tr_display_global_energy_item_ai AFTER INSERT ON biz_energy_item FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_energy_item_au AFTER UPDATE ON biz_energy_item FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_energy_item_ad AFTER DELETE ON biz_energy_item FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_energy_item_version_ai AFTER INSERT ON biz_energy_item_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_energy_item_version_au AFTER UPDATE ON biz_energy_item_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_energy_item_version_ad AFTER DELETE ON biz_energy_item_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_energy_item_scope_ai AFTER INSERT ON biz_energy_item_version_scope FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_energy_item_scope_au AFTER UPDATE ON biz_energy_item_version_scope FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_energy_item_scope_ad AFTER DELETE ON biz_energy_item_version_scope FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_measure_unit_ai AFTER INSERT ON biz_measurement_unit FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_measure_unit_au AFTER UPDATE ON biz_measurement_unit FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_measure_unit_ad AFTER DELETE ON biz_measurement_unit FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_measure_unit_version_ai AFTER INSERT ON biz_measurement_unit_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_measure_unit_version_au AFTER UPDATE ON biz_measurement_unit_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_measure_unit_version_ad AFTER DELETE ON biz_measurement_unit_version FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_quality_revision_ai AFTER INSERT ON biz_quality_usage_config_revision FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_quality_revision_au AFTER UPDATE ON biz_quality_usage_config_revision FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//
CREATE TRIGGER tr_display_global_quality_revision_ad AFTER DELETE ON biz_quality_usage_config_revision FOR EACH ROW BEGIN CALL bump_carbon_display_revision('*','DYNAMIC_INPUT','*'); END//

CREATE TRIGGER tr_display_daily_snapshot_ai AFTER INSERT ON biz_energy_period_result_snapshot FOR EACH ROW
BEGIN
    IF NEW.period_type='DAY' THEN
        CALL bump_carbon_display_local_month(NEW.building_id,'DAILY_INPUT',DATE(NEW.period_start));
    END IF;
END//
CREATE TRIGGER tr_display_daily_snapshot_au AFTER UPDATE ON biz_energy_period_result_snapshot FOR EACH ROW
BEGIN
    IF NOT(OLD.evidence_hash <=> NEW.evidence_hash) OR NOT(OLD.status <=> NEW.status) OR NOT(OLD.snapshot_version <=> NEW.snapshot_version)
       OR NOT(OLD.source_batch_id <=> NEW.source_batch_id) OR NOT(OLD.point_id <=> NEW.point_id) OR NOT(OLD.period_type <=> NEW.period_type)
       OR NOT(OLD.period_end <=> NEW.period_end) OR NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.period_start <=> NEW.period_start) THEN
        IF OLD.period_type='DAY' THEN
            CALL bump_carbon_display_local_month(OLD.building_id,'DAILY_INPUT',DATE(OLD.period_start));
        END IF;
        IF NEW.period_type='DAY' THEN
            CALL bump_carbon_display_local_month(NEW.building_id,'DAILY_INPUT',DATE(NEW.period_start));
        END IF;
    END IF;
END//
CREATE TRIGGER tr_display_daily_snapshot_ad AFTER DELETE ON biz_energy_period_result_snapshot FOR EACH ROW
BEGIN
    IF OLD.period_type='DAY' THEN
        CALL bump_carbon_display_local_month(OLD.building_id,'DAILY_INPUT',DATE(OLD.period_start));
    END IF;
END//

CREATE TRIGGER tr_display_recalculation_batch_ai AFTER INSERT ON biz_energy_recalculation_batch FOR EACH ROW
BEGIN CALL bump_display_recalculation_batch_window(NEW.batch_id,NEW.building_id); END//
CREATE TRIGGER tr_display_recalculation_batch_au AFTER UPDATE ON biz_energy_recalculation_batch FOR EACH ROW
BEGIN
    IF NOT(OLD.status <=> NEW.status) OR NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.batch_id <=> NEW.batch_id) THEN
        CALL bump_display_recalculation_batch_window(OLD.batch_id,OLD.building_id);
        CALL bump_display_recalculation_batch_window(NEW.batch_id,NEW.building_id);
    END IF;
END//
CREATE TRIGGER tr_display_recalculation_batch_ad AFTER DELETE ON biz_energy_recalculation_batch FOR EACH ROW
BEGIN CALL bump_display_recalculation_batch_window(OLD.batch_id,OLD.building_id); END//

CREATE TRIGGER tr_display_hourly_batch_ai AFTER INSERT ON biz_carbon_hourly_batch FOR EACH ROW
BEGIN CALL bump_carbon_display_local_month(NEW.building_id,'DAILY_RESULT',NEW.day_date); END//
CREATE TRIGGER tr_display_hourly_batch_au AFTER UPDATE ON biz_carbon_hourly_batch FOR EACH ROW
BEGIN
    IF NOT(OLD.evidence_hash <=> NEW.evidence_hash) OR NOT(OLD.view_hash <=> NEW.view_hash)
       OR NOT(OLD.batch_id <=> NEW.batch_id) OR NOT(OLD.revision <=> NEW.revision)
       OR NOT(OLD.calculation_as_of <=> NEW.calculation_as_of)
       OR NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.day_date <=> NEW.day_date) THEN
        CALL bump_carbon_display_local_month(OLD.building_id,'DAILY_RESULT',OLD.day_date);
        CALL bump_carbon_display_local_month(NEW.building_id,'DAILY_RESULT',NEW.day_date);
    END IF;
END//
CREATE TRIGGER tr_display_hourly_batch_ad AFTER DELETE ON biz_carbon_hourly_batch FOR EACH ROW
BEGIN CALL bump_carbon_display_local_month(OLD.building_id,'DAILY_RESULT',OLD.day_date); END//

CREATE TRIGGER tr_display_daily_static_ai AFTER INSERT ON biz_carbon_daily_static_batch FOR EACH ROW
BEGIN CALL bump_carbon_display_local_month(NEW.building_id,'DAILY_RESULT',NEW.day_date); END//
CREATE TRIGGER tr_display_daily_static_au AFTER UPDATE ON biz_carbon_daily_static_batch FOR EACH ROW
BEGIN
    IF NOT(OLD.evidence_hash <=> NEW.evidence_hash) OR NOT(OLD.view_hash <=> NEW.view_hash)
       OR NOT(OLD.batch_id <=> NEW.batch_id) OR NOT(OLD.revision <=> NEW.revision) OR NOT(OLD.source_version <=> NEW.source_version)
       OR NOT(OLD.calculation_as_of <=> NEW.calculation_as_of)
       OR NOT(OLD.building_id <=> NEW.building_id) OR NOT(OLD.day_date <=> NEW.day_date) THEN
        CALL bump_carbon_display_local_month(OLD.building_id,'DAILY_RESULT',OLD.day_date);
        CALL bump_carbon_display_local_month(NEW.building_id,'DAILY_RESULT',NEW.day_date);
    END IF;
END//
CREATE TRIGGER tr_display_daily_static_ad AFTER DELETE ON biz_carbon_daily_static_batch FOR EACH ROW
BEGIN CALL bump_carbon_display_local_month(OLD.building_id,'DAILY_RESULT',OLD.day_date); END//

DELIMITER ;
