package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定旧 HVAC 基线先归档后退役、规范菜单唯一生效的升级边界。 */
class LegacyHvacRetirementAndMenuGovernanceContractTest {

    private static final Path INIT = Path.of("src", "env", "init");

    @Test
    void archivesEveryRemovedDomainBeforeCleanupMigration() throws IOException {
        String archive = Files.readString(INIT.resolve(
                "V58__mysql_archive_legacy_hvac_baseline.sql"));
        String cleanup = Files.readString(INIT.resolve(
                "V59__mysql_retire_legacy_hvac_and_govern_menus.sql"));

        assertThat(archive).contains(
                "CREATE DATABASE IF NOT EXISTS `iot_platform_archive`",
                "legacy_hvac_archive_manifest",
                "`biz_equipment` LIKE `iot_platform`.`biz_equipment`",
                "`biz_data_point` LIKE `iot_platform`.`biz_data_point`",
                "`biz_point_alias` LIKE `iot_platform`.`biz_point_alias`",
                "`biz_indicator` LIKE `iot_platform`.`biz_indicator`",
                "`biz_collection_policy` LIKE `iot_platform`.`biz_collection_policy`",
                "`biz_quality_usage_policy` LIKE `iot_platform`.`biz_quality_usage_policy`",
                "`biz_quality_usage_review_request` LIKE `iot_platform`.`biz_quality_usage_review_request`",
                "`biz_quality_usage_change_set` LIKE `iot_platform`.`biz_quality_usage_change_set`",
                "`sys_menu` LIKE `iot_platform`.`sys_menu`",
                "(`request_id`,`building_id`,`target_type`,`target_id`,`target_config_revision`,`status`,",
                "FROM JSON_TABLE(r.`snapshot_json`, '$[*]' COLUMNS (`point_id` VARCHAR(32) PATH '$.pointId')) j",
                "V58_LEGACY_HVAC_BASELINE");
        assertThat(archive).doesNotContain("SELECT r.* FROM `iot_platform`.`biz_collection_review_request`");
        assertThat(cleanup).contains(
                "LEGACY_HVAC_ARCHIVE_INCOMPLETE",
                "LEGACY_HVAC_CUSTOM_ALIAS_REQUIRES_REVIEW",
                "LEGACY_HVAC_BUSINESS_REFERENCE_REQUIRES_REVIEW",
                "LEGACY_HVAC_COLLECTION_REVISION_REQUIRES_REVIEW",
                "LEGACY_HVAC_QUALITY_REVISION_REQUIRES_REVIEW",
                "DECLARE EXIT HANDLER FOR SQLEXCEPTION",
                "ROLLBACK",
                "START TRANSACTION",
                "COMMIT");
    }

    @Test
    void removesExactLegacyBaselineAndBuildsCanonicalMenuTree() throws IOException {
        String cleanup = Files.readString(INIT.resolve(
                "V59__mysql_retire_legacy_hvac_and_govern_menus.sql"));

        assertThat(cleanup).contains(
                "SOURCE_MQTT_STANDARD_V1",
                "DELETE FROM `biz_indicator`",
                "DELETE FROM `biz_point_alias`",
                "DELETE FROM `biz_data_point`",
                "DELETE FROM `biz_equipment`",
                "DELETE FROM `biz_data_source`",
                "'/operations'",
                "'/operations/realtime'",
                "'/operations/realtime/hvac'",
                "'/operations/devices/businessDevices'",
                "'/operations/devices/pendingDevices'",
                "'/monitor'",
                "'/monitor/monitoring'",
                "'/monitor/trend'",
                "'/monitor/situation'",
                "'/monitor/status'",
                "'/monitor/analysis'",
                "MONITOR_MENU_ID_CONFLICT",
                "SELECT `role_id`, 311 FROM `tmp_legacy_hvac_roles`",
                "'/configuration'",
                "'/configuration/access/users'",
                "'/configuration/settings/menus'",
                "'/configuration/space/buildings'",
                "'/configuration/ingestion/products'",
                "'/configuration/ingestion/protocols'",
                "ORPHAN_ROLE_MENU_LINK_AFTER_GOVERNANCE",
                "ORPHAN_MENU_NODE_AFTER_GOVERNANCE",
                "LEGACY_MENU_PATH_REMAINS");
        assertThat(cleanup).doesNotContain(
                "DELETE FROM `biz_point_alias` WHERE `source_system` = 'MQTT_FREEZE_V1'",
                "DELETE FROM `biz_data_point` WHERE `building_id` = 'BLD001'",
                "DELETE FROM `biz_equipment` WHERE `building_id` = 'BLD001'",
                "CROSS JOIN (SELECT 401 AS screen_id");
    }

    @Test
    void activeDefaultsAndTdengineBootstrapDoNotRecreateFreezeBaseline() throws IOException {
        String application = Files.readString(Path.of("src", "main", "resources", "application.yml"));
        String ingestion = Files.readString(Path.of("src", "main", "java", "com", "platform",
                "iot", "ingest", "HvacIngestionService.java"));
        String cache = Files.readString(Path.of("src", "main", "java", "com", "platform",
                "cache", "CacheConstants.java"));
        String tdengine = Files.readString(INIT.resolve("04-init-tdengine-hvac.sql"));

        assertThat(application).contains("INGESTION_SOURCE_SYSTEM:MQTT_STANDARD_V1");
        assertThat(ingestion).contains("ingestion.source-system:MQTT_STANDARD_V1");
        assertThat(cache).contains("menu:v2:user:", "menu:v2:all");
        assertThat(tdengine).doesNotContain(
                "INDICATOR_WCR_COP_B1",
                "INDICATOR_TOWER_EFF_B1",
                "INDICATOR_PUMP_EFF_B1",
                "INDICATOR_AHU_EFF_B1");
    }
}
