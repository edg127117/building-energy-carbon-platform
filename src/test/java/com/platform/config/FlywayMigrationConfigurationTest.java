package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定 MySQL Flyway 单一迁移源、唯一版本和旧库显式接管边界。 */
class FlywayMigrationConfigurationTest {

    private static final Path MIGRATION_ROOT = Path.of("src", "env", "init");
    private static final Pattern VERSIONED_NAME = Pattern.compile("V(\\d+)__.+\\.sql");

    @Test
    void packagesOnlyTheApprovedMysqlMigrationChain() throws IOException {
        List<String> migrations;
        try (var paths = Files.list(MIGRATION_ROOT)) {
            migrations = paths
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V"))
                    .sorted()
                    .toList();
        }

        assertThat(migrations).containsExactly(
                "V01__init_tables.sql",
                "V03__init_hvac_schema.sql",
                "V08__mysql_data_quality_fill.sql",
                "V10__mysql_data_quality_recalculation.sql",
                "V11__mysql_admin_menu_runtime.sql",
                "V12__device_identity.sql",
                "V13__device_onboarding_discovery.sql",
                "V14__device_onboarding_binding.sql",
                "V15__mysql_asset_management_menu.sql",
                "V16__mysql_device_onboarding_menu.sql",
                "V17__mysql_collection_policy_governance.sql",
                "V18__mysql_quality_usage_policy_governance.sql",
                "V20__mysql_device_parameter_governance.sql",
                "V22__mysql_telemetry_reliability_v2.sql",
                "V23__optimize_telemetry_receipt_retention.sql",
                "V24__mysql_relation_governance.sql",
                "V25__mysql_audit_governance_foundation.sql",
                "V26__mysql_account_activation_token.sql",
                "V27__close_collection_direct_publication.sql",
                "V28__close_quality_usage_direct_publication.sql",
                "V29__mysql_audit_query_redacted_export.sql",
                "V30__mysql_audit_retention_hold_cleanup.sql",
                "V31__mysql_energy_point_profile.sql",
                "V32__mysql_meter_structure_import.sql",
                "V33__mysql_energy_activity_quality_scenario.sql",
                "V34__mysql_energy_catalog_unit_binding.sql");

        Set<String> versions = migrations.stream()
                .map(VERSIONED_NAME::matcher)
                .peek(matcher -> assertThat(matcher.matches()).isTrue())
                .map(matcher -> matcher.group(1))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(versions).hasSameSizeAs(migrations);
    }

    @Test
    void applicationUsesFlywayButTestsKeepMysqlMigrationsDisabled() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        String application = Files.readString(Path.of(
                "src", "main", "resources", "application.yml"));
        String testApplication = Files.readString(Path.of(
                "src", "test", "resources", "application-test.yml"));

        assertThat(pom).contains(
                "<artifactId>flyway-core</artifactId>",
                "<artifactId>flyway-mysql</artifactId>",
                "<directory>src/env/init</directory>",
                "<targetPath>db/migration/mysql</targetPath>",
                "<include>V*.sql</include>");
        assertThat(application).contains(
                "locations: classpath:db/migration/mysql",
                "baseline-on-migrate: ${MYSQL_FLYWAY_BASELINE_ON_MIGRATE:false}",
                "baseline-version: ${MYSQL_FLYWAY_BASELINE_VERSION:3}",
                "clean-disabled: true");
        assertThat(testApplication).contains("flyway:", "enabled: false");
    }
}
