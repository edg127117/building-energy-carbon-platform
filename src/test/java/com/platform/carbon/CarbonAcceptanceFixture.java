package com.platform.carbon;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 一次性 MySQL 的显式合成基线；不导入正式因子、真实建筑或任何现场资源。 */
final class CarbonAcceptanceFixture implements AutoCloseable {
    final JdbcTemplate jdbc;
    private final HikariDataSource source;

    CarbonAcceptanceFixture() {
        assertThat(System.getenv("CARBON_ACCEPTANCE_ISOLATED")).isEqualTo("true");
        String url = System.getenv("CARBON_ACCEPTANCE_URL");
        assertThat(url).matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/iot_platform\\?.+");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv().getOrDefault("CARBON_ACCEPTANCE_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("CARBON_ACCEPTANCE_PASSWORD", ""));
        config.setMaximumPoolSize(4);
        config.setPoolName("acceptance-fixture-only");
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        int tables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                """, Integer.class);
        if (tables == 0) {
            Flyway.configure().dataSource(source).locations("filesystem:src/env/init").load().migrate();
            jdbc.execute("CREATE TABLE acceptance_schema_marker (purpose VARCHAR(100) PRIMARY KEY)");
            jdbc.update("INSERT INTO acceptance_schema_marker VALUES ('DISPOSABLE_CARBON_ACCEPTANCE_ONLY')");
            jdbc.execute("""
                    CREATE TABLE acceptance_activity (
                      snapshot_id VARCHAR(32) PRIMARY KEY, building_id VARCHAR(32) NOT NULL,
                      start_at DATETIME(3) NOT NULL, end_at DATETIME(3) NOT NULL,
                      energy_item VARCHAR(64) NOT NULL, quantity DECIMAL(38,18) NOT NULL,
                      unit_code VARCHAR(32) NOT NULL, nature VARCHAR(32) NOT NULL,
                      KEY idx_acceptance_activity (building_id,start_at,end_at))
                    """);
            jdbc.execute("""
                    CREATE TABLE acceptance_activity_control (
                      building_id VARCHAR(32) PRIMARY KEY, delay_ms BIGINT NOT NULL DEFAULT 0,
                      fail_read INT NOT NULL DEFAULT 0, read_count INT NOT NULL DEFAULT 0,
                      read_in_transaction BOOLEAN NOT NULL DEFAULT FALSE)
                    """);
        }
        assertThat(jdbc.queryForObject("SELECT purpose FROM acceptance_schema_marker", String.class))
                .isEqualTo("DISPOSABLE_CARBON_ACCEPTANCE_ONLY");
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history",
                Integer.class)).isEqualTo(39);
    }

    void reset() {
        // 仅清理上方空库初始化并带专用标记的测试库，按外键顺序列举表，不禁用约束。
        for (String table : List.of("biz_carbon_recalculation_batch_trigger", "biz_carbon_result_relation",
                "biz_carbon_recalculation_item", "biz_carbon_recalculation_batch",
                "biz_carbon_recalculation_trigger", "biz_carbon_dependency_change",
                "biz_carbon_calculation_summary", "biz_carbon_calculation_failure",
                "biz_carbon_calculation_item")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE biz_carbon_calculation_batch SET supersedes_calculation_batch_id=NULL");
        jdbc.update("DELETE FROM biz_carbon_calculation_batch");
        for (String table : List.of("biz_carbon_denominator_version", "biz_carbon_denominator",
                "biz_carbon_factor_component", "biz_carbon_factor_version", "biz_carbon_factor",
                "biz_carbon_factor_source_version", "biz_carbon_factor_source",
                "acceptance_activity", "acceptance_activity_control")) jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM sys_security_audit_event WHERE source_module IN ('CARBON_MANAGEMENT','SYSTEM_SECURITY')");
        for (long user : List.of(9001L, 9002L, 9003L)) {
            jdbc.update("DELETE FROM sys_user_backend_duty WHERE user_id=?", user);
            jdbc.update("""
                    INSERT INTO sys_user_backend_duty
                    (assignment_id,user_id,duty_key,status,effective_at,created_by)
                    SELECT REPLACE(UUID(),'-',''),?,duty_key,'ACTIVE','2000-01-01',9002
                    FROM sys_backend_duty WHERE duty_key LIKE 'CARBON_%'
                    """, user);
        }
        for (String nature : List.of("FORMAL", "DEVELOPMENT_REFERENCE")) {
            factor(nature, "ELECTRICITY", "PURCHASED_ELECTRICITY_LOCATION", "KWH", "0.5",
                    "CFV_ELECTRICITY_CO2E_V1");
            factor(nature, "HEAT", "PURCHASED_HEAT", "GJ", "100", "CFV_HEAT_CO2E_V1");
        }
    }

    private void factor(String nature, String energy, String category, String unit, String value, String formula) {
        String source = id(); String version = id(); String factor = id(); String factorVersion = id();
        jdbc.update("INSERT INTO biz_carbon_factor_source(source_id,source_code,created_by) VALUES (?,?,9001)",
                source, source);
        jdbc.update("""
                INSERT INTO biz_carbon_factor_source_version
                (source_version_id,source_id,version_no,source_name,publisher,document_reference,
                 applicability_note,evidence_reference,usage_nature,created_by)
                VALUES (?,?,1,'SYNTHETIC ONLY','acceptance fixture','not a business factor',
                        'isolated software testing','synthetic fixture',?,9001)
                """, version, source, nature);
        jdbc.update("""
                INSERT INTO biz_carbon_factor
                (factor_id,factor_code,scope_type,energy_item_code,factor_category,result_basis,
                 gas_code,gas_coverage,created_by)
                VALUES (?,?,'SCOPE_2',?,?,'CO2E_DIRECT','CO2e','TEST_CO2E',9001)
                """, factor, factor, energy, category);
        jdbc.update("""
                INSERT INTO biz_carbon_factor_version
                (factor_version_id,factor_id,version_no,source_version_id,applicability_level,
                 input_unit_code,usage_nature,status,effective_from,formula_version_id,
                 rounding_policy_version_id,created_by,reviewed_by,activated_by)
                VALUES (?,?,1,?,'NATIONAL',?,?,'ACTIVE','2000-01-01',?,
                        'CRP_DECIMAL128_V1',9001,9002,9002)
                """, factorVersion, factor, version, unit, nature, formula);
        jdbc.update("""
                INSERT INTO biz_carbon_factor_component
                (component_id,factor_version_id,component_type,component_value,component_unit,
                 source_version_id,evidence_reference)
                VALUES (?,?,'DIRECT_EMISSION_FACTOR',?,?,?,'synthetic fixture')
                """, id(), factorVersion, value, "KG_CO2E/" + unit, version);
    }

    void building(String building, int year, String nature, int count) {
        jdbc.update("""
                INSERT IGNORE INTO building
                (building_id,building_name,building_type,total_gfa,climate_zone,region_code)
                VALUES (?,'SYNTHETIC ACCEPTANCE','办公',1,'夏热冬冷','330100')
                """, building);
        jdbc.update("INSERT IGNORE INTO acceptance_activity_control(building_id) VALUES (?)", building);
        for (int i = 0; i < count; i++) {
            LocalDate month = LocalDate.of(year, i % 12 + 1, 1);
            jdbc.update("""
                    INSERT INTO acceptance_activity
                    (snapshot_id,building_id,start_at,end_at,energy_item,quantity,unit_code,nature)
                    VALUES (?,?,?,?,?,100,?,?)
                    """, id(), building, stamp(month), stamp(month.plusMonths(1)),
                    i % 2 == 0 ? "ELECTRICITY" : "HEAT", i % 2 == 0 ? "KWH" : "GJ", nature);
        }
    }

    void denominator(String building, String type, String value, String unit, String nature) {
        String denominator = id();
        jdbc.update("""
                INSERT INTO biz_carbon_denominator(denominator_id,building_id,denominator_type,created_by)
                VALUES (?,?,?,9001)
                """, denominator, building, type);
        jdbc.update("""
                INSERT INTO biz_carbon_denominator_version
                (denominator_version_id,denominator_id,version_no,denominator_value,unit_code,
                 source_reference,evidence_reference,usage_nature,status,effective_from,created_by)
                VALUES (?,?,1,?,?,'synthetic fixture','synthetic fixture',?,'ACTIVE','2000-01-01',9001)
                """, id(), denominator, value, unit, nature);
    }

    String change(String building, String boundary, int fromYear, int toYear, String reason) {
        String id = id();
        new CarbonRuleRepository(jdbc).insertDependencyChange(id, reason, "TEST_SNAPSHOT", id,
                "isolated version change", null, id, CarbonCalculationCore.sha256(id), building,
                boundary, LocalDate.of(fromYear, 1, 1).atStartOfDay(),
                LocalDate.of(toYear + 1, 1, 1).atStartOfDay(), 9001L, LocalDateTime.now());
        return id;
    }

    static Map<String, Object> calculation(String building, int year, String nature, String key) {
        return Map.of("buildingId", building, "periodType", "YEAR", "timezoneId", "Asia/Shanghai",
                "startInclusive", LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString(),
                "endExclusive", LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString(),
                "resultNature", nature, "idempotencyKey", key);
    }

    static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    @Override public void close() { source.close(); }
    private static Timestamp stamp(LocalDate date) {
        return Timestamp.from(date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }
}
