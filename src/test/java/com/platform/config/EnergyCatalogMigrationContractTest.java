package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyCatalogMigrationContractTest {

    @Test
    void migrationPublishesVersionedCatalogWithoutApprovedProfessionalDefaults() throws Exception {
        String sql = Files.readString(Path.of("src", "env", "init",
                "V34__mysql_energy_catalog_unit_binding.sql"), StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "CREATE TABLE `biz_energy_item`",
                "CREATE TABLE `biz_energy_item_version`",
                "CREATE TABLE `biz_measurement_unit_version`",
                "CREATE TABLE `biz_energy_item_unit_compatibility_version`",
                "CREATE TABLE `biz_energy_point_item_binding_version`",
                "'ENERGY_CATALOG_MAINTAIN'",
                "'ENERGY_CATALOG_REVIEW'",
                "'BITUMINOUS_COAL'",
                "'ELECTRICITY'",
                "'HEAT'",
                "'TEN_THOUSAND_NM3'",
                "'PENDING_EXPERT'",
                "仅研发模拟，生产不可用");
        assertThat(sql).doesNotContain(
                "INSERT INTO `biz_energy_item_unit_compatibility`",
                "'MOBILE_COMBUSTION'),",
                "tCO2",
                "carbon_factor");
    }

    @Test
    void h2SchemaMirrorsImmutableVersionsAndPendingBaseline() throws Exception {
        String schema = Files.readString(Path.of("src", "test", "resources", "schema-test.sql"),
                StandardCharsets.UTF_8);
        String data = Files.readString(Path.of("src", "test", "resources", "data-test.sql"),
                StandardCharsets.UTF_8);

        assertThat(schema).contains(
                "CREATE TABLE biz_energy_item_version",
                "CREATE TABLE biz_measurement_unit_version",
                "CREATE TABLE biz_energy_item_unit_compatibility_version",
                "CREATE TABLE biz_energy_point_item_binding_version",
                "UNIQUE (binding_id,binding_version)");
        assertThat(data).contains("'EIV_ELECTRICITY_1'", "'EUV_KWH_1'", "'PENDING_EXPERT'");
        assertThat(data).doesNotContain("INSERT INTO biz_energy_item_unit_compatibility ");
    }
}
