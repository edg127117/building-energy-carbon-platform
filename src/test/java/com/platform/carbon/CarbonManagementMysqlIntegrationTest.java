package com.platform.carbon;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在调用方确认的一次性 MySQL 8 空库验证 V39 结构、种子边界和关键检查约束。 */
@EnabledIfEnvironmentVariable(named = "CARBON_MYSQL_IT_URL", matches = ".+")
class CarbonManagementMysqlIntegrationTest {

    @Test
    void migratesFullChainAndEnforcesCarbonFoundationConstraints() {
        assertThat(System.getenv("CARBON_MYSQL_IT_ISOLATED"))
                .as("集成测试必须显式确认一次性隔离 MySQL").isEqualTo("true");
        String url = System.getenv("CARBON_MYSQL_IT_URL");
        assertThat(url).contains("/iot_platform");
        DataSource dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("CARBON_MYSQL_IT_USER", "root"),
                System.getenv().getOrDefault("CARBON_MYSQL_IT_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                Integer.class)).isZero();

        Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init")
                .load().migrate();

        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success=1 ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("39");
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()
                  AND table_name LIKE 'biz_carbon_%'
                """, String.class)).contains(
                "biz_carbon_factor_version", "biz_carbon_denominator_version",
                "biz_carbon_calculation_batch", "biz_carbon_recalculation_batch",
                "biz_carbon_recalculation_item");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_factor", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_denominator", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_backend_duty WHERE duty_key LIKE 'CARBON_%'
                """, Integer.class)).isEqualTo(6);

        jdbc.update("""
                INSERT INTO biz_carbon_denominator
                  (denominator_id,building_id,denominator_type,created_by)
                VALUES ('DEN001','BLD001','BUILDING_AREA',1)
                """);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO biz_carbon_denominator_version
                  (denominator_version_id,denominator_id,version_no,denominator_value,unit_code,
                   source_reference,evidence_reference,usage_nature,status,effective_from,
                   config_revision,created_by)
                VALUES ('DENV001','DEN001',1,0,'M2','测试来源','测试证据',
                        'DEVELOPMENT_REFERENCE','PENDING_REVIEW','2026-01-01',0,1)
                """)).isInstanceOf(DataAccessException.class);
    }
}
