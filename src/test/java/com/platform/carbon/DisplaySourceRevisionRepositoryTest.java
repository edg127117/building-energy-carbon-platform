package com.platform.carbon;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

class DisplaySourceRevisionRepositoryTest {
    JdbcTemplate jdbc;
    DisplaySourceRevisionRepository revisions;

    @Test void futureCatalogApprovalDoesNotInvalidateOctoberButRelevantChangesRejectOldPublish() {
        jdbc.execute("CREATE TABLE biz_energy_item(item_id VARCHAR(32),item_code VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_energy_item_version(item_id VARCHAR(32),version_id VARCHAR(32),config_revision INT,status VARCHAR(24),effective_from TIMESTAMP,effective_to TIMESTAMP)");
        jdbc.execute("CREATE TABLE biz_energy_item_version_scope(version_id VARCHAR(32),usage_scope VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_measurement_unit(unit_id VARCHAR(32),unit_code VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_measurement_unit_version(unit_id VARCHAR(32),version_id VARCHAR(32),config_revision INT,status VARCHAR(24),effective_from TIMESTAMP,effective_to TIMESTAMP,symbol VARCHAR(32),dimension_code VARCHAR(40),canonical_unit_code VARCHAR(64),scale_factor DECIMAL(24,12),conversion_type VARCHAR(32),standard_condition_code VARCHAR(100))");
        jdbc.update("INSERT INTO biz_carbon_display_source_revision VALUES ('*','CATALOG','*',1)");
        jdbc.update("INSERT INTO biz_measurement_unit VALUES ('K','KWH')");
        jdbc.update("INSERT INTO biz_measurement_unit_version(unit_id,version_id,config_revision,status,effective_from,effective_to) VALUES ('K','october',1,'APPROVED','2026-10-01 00:00:00',NULL)");
        var from=java.time.Instant.parse("2026-09-30T16:00:00Z");var to=java.time.Instant.parse("2026-10-31T16:00:00Z");
        String october=revisions.version("B","DYNAMIC",from,to);
        jdbc.update("UPDATE biz_measurement_unit_version SET effective_to='2026-11-01 00:00:00' WHERE version_id='october'");
        jdbc.update("INSERT INTO biz_measurement_unit_version(unit_id,version_id,config_revision,status,effective_from,effective_to) VALUES ('K','november',1,'APPROVED','2026-11-09 00:00:00',NULL)");
        jdbc.update("UPDATE biz_carbon_display_source_revision SET revision=revision+1 WHERE domain_key='CATALOG'");
        assertThat(revisions.version("B","DYNAMIC",from,to)).isEqualTo(october);
        assertThat(revisions.ifCurrent("B","DYNAMIC",from,to,october,()->{})).isTrue();
        jdbc.update("UPDATE biz_measurement_unit_version SET config_revision=2 WHERE version_id='october'");
        jdbc.update("UPDATE biz_carbon_display_source_revision SET revision=revision+1 WHERE domain_key='CATALOG'");
        assertThat(revisions.version("B","DYNAMIC",from,to)).isNotEqualTo(october);
        assertThat(revisions.ifCurrent("B","DYNAMIC",from,to,october,()->{throw new AssertionError("stale publish");})).isFalse();
    }

    @BeforeEach
    void setUp() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:display_revision_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE biz_carbon_display_source_revision(
                    building_id VARCHAR(32) NOT NULL,
                    domain_key VARCHAR(32) NOT NULL,
                    scope_key VARCHAR(16) NOT NULL,
                    revision BIGINT NOT NULL,
                    PRIMARY KEY(building_id,domain_key,scope_key))
                """);
        jdbc.execute("CREATE TABLE display_result(result_value INT NOT NULL)");
        revisions = new DisplaySourceRevisionRepository(jdbc, new DataSourceTransactionManager(source));
    }

    @Test
    void firstBuildCanPublishWhenItsRevisionRowsHaveNotBeenSeeded() {
        String initial = revisions.version("building-a", "DAILY", java.time.Instant.parse("2026-08-31T16:00:00Z"),
                java.time.Instant.parse("2026-09-30T16:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_display_source_revision", Integer.class)).isZero();

        boolean published = revisions.ifCurrent("building-a", "DAILY", java.time.Instant.parse("2026-08-31T16:00:00Z"),
                java.time.Instant.parse("2026-09-30T16:00:00Z"), initial,
                () -> jdbc.update("INSERT INTO display_result(result_value) VALUES (1)"));
        assertThat(published).isTrue();
    }

    @Test
    void unrelatedBuildingAndMonthDoNotInvalidateTheWindow() {
        var from = java.time.Instant.parse("2026-08-31T16:00:00Z");
        var to = java.time.Instant.parse("2026-09-30T16:00:00Z");
        String expected = revisions.version("building-a", "DYNAMIC", from, to);
        jdbc.update("INSERT INTO biz_carbon_display_source_revision VALUES ('building-b','DYNAMIC_INPUT','U:2026-09',1)");
        jdbc.update("INSERT INTO biz_carbon_display_source_revision VALUES ('building-a','DYNAMIC_INPUT','U:2026-10',1)");
        assertThat(revisions.version("building-a", "DYNAMIC", from, to)).isEqualTo(expected);
    }

    @Test
    void monthlySourceChangeRejectsOldBuildAndRunsNoPublishCallback() {
        var from = java.time.Instant.parse("2026-08-31T16:00:00Z");
        var to = java.time.Instant.parse("2026-09-30T16:00:00Z");
        String expected = revisions.version("building-a", "DYNAMIC", from, to);
        jdbc.update("INSERT INTO biz_carbon_display_source_revision VALUES ('building-a','DYNAMIC_INPUT','U:2026-09',1)");
        var attempts = new java.util.concurrent.atomic.AtomicInteger();

        boolean published = revisions.ifCurrent("building-a", "DYNAMIC", from, to, expected, attempts::incrementAndGet);

        assertThat(published).isFalse();
        assertThat(attempts).hasValue(0);
    }

    @Test
    void sourceAndResultDomainsAreBothPartOfCombinedVersion() {
        var from = java.time.Instant.parse("2026-08-31T16:00:00Z");
        var to = java.time.Instant.parse("2026-09-30T16:00:00Z");
        String expected = revisions.version("building-a", "DYNAMIC", from, to);
        jdbc.update("INSERT INTO biz_carbon_display_source_revision VALUES ('building-a','DYNAMIC_RESULT','U:2026-09',1)");

        assertThat(revisions.version("building-a", "DYNAMIC", from, to)).isNotEqualTo(expected);
    }

    @Test
    void globalMetadataInvalidatesEveryBuildingWindow() {
        var from = java.time.Instant.parse("2026-08-31T16:00:00Z");
        var to = java.time.Instant.parse("2026-09-30T16:00:00Z");
        String expected = revisions.version("building-a", "DYNAMIC", from, to);
        jdbc.update("INSERT INTO biz_carbon_display_source_revision VALUES ('*','DYNAMIC_INPUT','*',1)");

        assertThat(revisions.version("building-a", "DYNAMIC", from, to)).isNotEqualTo(expected);
    }
}
