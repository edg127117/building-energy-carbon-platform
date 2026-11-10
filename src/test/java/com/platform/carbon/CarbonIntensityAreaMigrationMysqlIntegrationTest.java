package com.platform.carbon;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅允许专用回环实例：先运行主线迁移，再验证V94至V96接续和旧面积证据保留。 */
@EnabledIfEnvironmentVariable(named = "SCREEN_INTENSITY_MYSQL_ISOLATED", matches = "true")
class CarbonIntensityAreaMigrationMysqlIntegrationTest {
    @Test void migratesTheRealChainThroughBothCandidatesWithoutChangingExistingBuildingOrAreaEvidence() {
        String url = System.getenv("SCREEN_INTENSITY_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/iot_platform\\?.+");
        var dataSource = new DriverManagerDataSource(url, "root", System.getenv("SCREEN_INTENSITY_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isZero();
        var baseline = Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init").target("93").load();
        baseline.migrate();
        assertThat(baseline.info().current().getVersion().toString()).isEqualTo("93");
        jdbc.update("""
                INSERT INTO building(building_id,building_name,building_code,building_type,total_gfa,climate_zone,region_code)
                VALUES ('IT_BUILDING','隔离迁移测试楼','IT_ONLY','办公',10000,'夏热冬冷','320115')
                """);
        jdbc.update("INSERT INTO biz_carbon_denominator(denominator_id,building_id,denominator_type,created_by) VALUES ('IT_AREA','IT_BUILDING','BUILDING_AREA',1)");
        for (int i = 1; i <= 2; i++) jdbc.update("""
                INSERT INTO biz_carbon_denominator_version(denominator_version_id,denominator_id,version_no,denominator_value,
                    unit_code,source_reference,evidence_reference,usage_nature,status,effective_from,created_by)
                VALUES (?,'IT_AREA',?,10000,'M2','test-source','test-evidence','FORMAL',?,'2024-01-01',1)
                """, "IT_VERSION_" + i, i, i == 1 ? "ACTIVE" : "DISABLED");
        int buildings = jdbc.queryForObject("SELECT COUNT(*) FROM building", Integer.class);
        var before = jdbc.queryForList("SELECT * FROM biz_carbon_denominator_version WHERE denominator_id='IT_AREA' ORDER BY version_no");

        var candidates = Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init").load();
        var result = candidates.migrate();
        assertThat(result.migrationsExecuted).isEqualTo(3);
        assertThat(candidates.info().current().getVersion().toString()).isEqualTo("96");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_plan_version", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_plan_scope", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM building", Integer.class)).isEqualTo(buildings);
        assertThat(jdbc.queryForObject("SELECT building_name FROM building WHERE building_id='IT_BUILDING'", String.class)).isEqualTo("隔离迁移测试楼");
        assertThat(jdbc.queryForObject("SELECT carbon_evaluation_category FROM building WHERE building_id='IT_BUILDING'", String.class)).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM biz_carbon_denominator_version WHERE denominator_id='IT_AREA' ORDER BY version_no")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_area_applicability WHERE denominator_version_id='IT_VERSION_1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_area_applicability WHERE denominator_version_id='IT_VERSION_2'", Integer.class)).isZero();
        var repository = new CarbonRuleRepository(jdbc);
        assertThat(repository.areaSlices("IT_BUILDING", CarbonModels.UsageNature.FORMAL,
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)))
                .extracting(part -> part.version().denominatorVersionId()).containsExactly("IT_VERSION_1");
        var directory = new CarbonIntensityCatalogRepository(jdbc).versions();
        assertThat(directory).hasSize(1);
        assertThat(directory.getFirst().entries()).hasSize(6).allSatisfy(rule -> {
            assertThat(rule.timeStatus()).isEqualTo("PENDING");
            assertThat(rule.applicableFrom()).isNull();
        });
        // 再次运行只验证迁移记录，不重复录入目录或面积区间。
        assertThat(candidates.migrate().migrationsExecuted).isZero();
    }
}
