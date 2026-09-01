package com.platform.energy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 专用一次性 MySQL 8 验证第七闭环完整迁移链、硬约束和关键查询索引。 */
@EnabledIfEnvironmentVariable(named = "ENERGY_LOOP7_MYSQL_IT_URL", matches = ".+")
class EnergyLoop7MysqlIntegrationTest {
    private static final List<String> LOOP_TABLES = List.of(
            "biz_energy_item_version",
            "biz_energy_conversion_parameter_version",
            "biz_energy_meter_event_version",
            "biz_energy_period_result_snapshot",
            "biz_energy_boundary_summary_policy_version");

    @Test
    void migratesEmptyDatabaseAndEnforcesLoopGovernance() {
        String url = System.getenv("ENERGY_LOOP7_MYSQL_IT_URL");
        assertThat(System.getenv("ENERGY_LOOP7_MYSQL_IT_ISOLATED"))
                .as("集成测试必须由调用方显式确认使用一次性隔离 MySQL")
                .isEqualTo("true");
        assertThat(url).as("隔离集成测试固定使用项目数据库名").contains("/iot_platform");
        DataSource dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("ENERGY_LOOP7_MYSQL_IT_USER", "root"),
                System.getenv().getOrDefault("ENERGY_LOOP7_MYSQL_IT_PASSWORD", "test-root"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                Integer.class)).as("隔离库必须为空").isZero();

        Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init")
                .load().migrate();

        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success=1 ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("38");
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                """, String.class)).containsAll(LOOP_TABLES);
        assertThat(jdbc.queryForList("""
                SELECT index_name FROM information_schema.statistics
                WHERE table_schema=DATABASE()
                  AND table_name IN ('biz_energy_period_result_snapshot',
                                     'biz_energy_boundary_summary_policy_version')
                """, String.class)).contains(
                        "idx_energy_period_snapshot_visible",
                        "idx_energy_boundary_summary_policy_effective");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO biz_energy_recalculation_batch
                  (batch_id,building_id,idempotency_key,request_hash,mode,status,reason,
                   total_items,submitted_by,submitted_at)
                VALUES ('LOOP7_BAD_COUNT','BLD001','loop7-bad-count',REPEAT('a',64),
                        'SAME_RULES','CREATED','隔离库约束验证',0,1,CURRENT_TIMESTAMP)
                """)).isInstanceOf(DataAccessException.class);

        jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy
                  (policy_id,building_id,metering_boundary_id,energy_item_code,created_by)
                VALUES ('LOOP7_POLICY','BLD001','LOOP7_BOUNDARY','ELECTRICITY',1)
                """);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy_version
                  (version_id,policy_id,version_no,aggregation_mode,status,source_type,
                   evidence_reference,effective_from,created_by)
                VALUES ('LOOP7_POLICY_BAD','LOOP7_POLICY',1,'MAIN_METER_TOTAL',
                        'PENDING_EXPERT','MANUAL','隔离库约束验证','2026-01-01',1)
                """)).isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForList("""
                EXPLAIN SELECT snapshot_id FROM biz_energy_period_result_snapshot
                FORCE INDEX (idx_energy_period_snapshot_visible)
                WHERE building_id='BLD001' AND point_id='POINT004' AND period_type='MONTH'
                  AND period_start >= '2026-01-01' AND status <> 'SUPERSEDED'
                """)).singleElement().satisfies(row ->
                assertThat(row.get("key")).isEqualTo("idx_energy_period_snapshot_visible"));
    }
}
