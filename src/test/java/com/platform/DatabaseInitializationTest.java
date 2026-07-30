package com.platform;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证测试环境基础 schema 可以一次性初始化人工重算所需的 MySQL 业务结构。
 *
 * <p>这里只检查 H2 中的表和字段，不连接真实 MySQL 或 TDengine；生产环境的
 * 增量升级由独立 migration 10 完成。</p>
 */
class DatabaseInitializationTest {

    @Test
    void initializesRecalculationJobAndFillTaskLink() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:recalc-schema;MODE=MySQL;"
                        + "DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        dataSource.setUser("sa");
        ResourceDatabasePopulator schema =
                new ResourceDatabasePopulator(
                        new ClassPathResource("schema-test.sql"),
                        new ClassPathResource("data-test.sql"));
        DatabasePopulatorUtils.execute(schema, dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        assertThat(columns(
                jdbcTemplate, "BIZ_DATA_QUALITY_RECALC_JOB"))
                .contains(
                        "JOB_ID",
                        "IDEMPOTENCY_KEY",
                        "STATUS",
                        "PHASE",
                        "CURSOR_MINUTE",
                        "VOID_TARGET_MINUTES_JSON",
                        "Q0_COUNT",
                        "Q1_COUNT",
                        "Q2_COUNT",
                        "MISSING_COUNT");
        assertThat(columns(
                jdbcTemplate, "BIZ_DATA_QUALITY_FILL_TASK"))
                .contains("RECALC_JOB_ID");
        assertThat(tableNames(jdbcTemplate))
                .doesNotContain(
                        "IOT_DEVICE",
                        "IOT_DEVICE_STATUS_LOG",
                        "CONTROL_COMMANDS");
        assertThat(jdbcTemplate.queryForList(
                "SELECT role_key FROM sys_role ORDER BY role_key",
                String.class))
                .containsExactly(
                        "BUILDING_OWNER",
                        "ENERGY_MANAGER",
                        "PLATFORM_ADMIN",
                        "THIRD_PARTY");
    }

    private Set<String> columns(
            JdbcTemplate jdbcTemplate,
            String tableName) {
        return Set.copyOf(jdbcTemplate.queryForList(
                """
                SELECT UPPER(COLUMN_NAME)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = ?
                """,
                String.class,
                tableName));
    }

    private List<String> tableNames(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForList(
                """
                SELECT UPPER(TABLE_NAME)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = 'PUBLIC'
                """,
                String.class);
    }
}
