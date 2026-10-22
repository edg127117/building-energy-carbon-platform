package com.platform.tools;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.Set;

/** 独立维护进程，只运行 Flyway，不启动采集、审核、重算或定时任务。 */
public final class D01SchemaMigration {
    private static final Set<String> TABLES = Set.of("biz_telemetry_raw_intent",
            "biz_telemetry_conflict_evidence", "biz_telemetry_conflict_owner", "biz_telemetry_resolution",
            "biz_telemetry_retention_boundary", "biz_telemetry_conflict_budget", "biz_telemetry_message_intent",
            "biz_telemetry_message_conflict", "biz_telemetry_change_task");

    private D01SchemaMigration() { }

    public static void main(String[] args) {
        if (args.length != 1 || !Set.of("validate83", "upgrade84", "validate84").contains(args[0])) {
            throw new IllegalArgumentException("D01_SCHEMA_ACTION_REQUIRED");
        }
        run(args[0], System.getenv());
    }

    static void run(String action, Map<String, String> environment) {
        String url = required(environment, "D01_JDBC_URL");
        if (!url.startsWith("jdbc:mysql://") || url.matches("(?is).*[?&](user|password)=.*")) {
            throw new IllegalArgumentException("D01_JDBC_URL_WITHOUT_CREDENTIALS_REQUIRED");
        }
        String user = required(environment, "D01_JDBC_USER");
        String password = required(environment, "D01_JDBC_PASSWORD");
        var source = new DriverManagerDataSource(url, user, password);
        var jdbc = new JdbcTemplate(source);
        if (!"iot_platform".equals(jdbc.queryForObject("SELECT DATABASE()", String.class))) {
            throw new IllegalStateException("D01_BUSINESS_DATABASE_REQUIRED");
        }
        // 不提供 baseline/repair/clean：部分 DDL 失败必须保留现场，不能把失败历史伪装为成功。
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration/mysql")
                .target(action.equals("validate83") ? "83" : "84")
                .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).load();
        String expected = action.equals("validate84") ? "84" : "83";
        var current = flyway.info().current();
        if (current == null || !expected.equals(current.getVersion().getVersion())) {
            throw new IllegalStateException("D01_EXPECTED_SCHEMA_" + expected);
        }
        if (action.equals("upgrade84")) {
            if (!"true".equals(environment.get("D01_WRITERS_FROZEN"))) {
                throw new IllegalStateException("D01_WRITERS_MUST_BE_FROZEN");
            }
            // 先按 V83 校验已部署历史；V84 此时应为 pending，由 migrate 校验并执行。
            Flyway.configure().dataSource(source).locations("classpath:db/migration/mysql")
                    .target("83").baselineOnMigrate(false).cleanDisabled(true).load().validate();
            if (flyway.migrate().migrationsExecuted != 1) {
                throw new IllegalStateException("D01_EXACTLY_ONE_MIGRATION_REQUIRED");
            }
        }
        flyway.validate();
        if (!action.equals("validate83")) {
            var actual = Set.copyOf(jdbc.queryForList("""
                    SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()
                    AND table_name IN ('biz_telemetry_raw_intent','biz_telemetry_conflict_evidence',
                    'biz_telemetry_conflict_owner','biz_telemetry_resolution','biz_telemetry_retention_boundary',
                    'biz_telemetry_conflict_budget','biz_telemetry_message_intent',
                    'biz_telemetry_message_conflict','biz_telemetry_change_task')
                    """, String.class));
            if (!actual.equals(TABLES) || jdbc.queryForObject("""
                    SELECT COUNT(*) FROM biz_telemetry_conflict_budget WHERE used_rows>=0
                    AND budget_key IN ('EVIDENCE','MESSAGE_EVIDENCE','PENDING_RAW','PENDING_MESSAGE','PENDING_DERIVED')
                    """, Integer.class) != 5) {
                throw new IllegalStateException("D01_SCHEMA_INCOMPLETE");
            }
        }
        System.out.println("D01_SCHEMA_VALIDATED=" + (action.equals("validate83") ? "83" : "84"));
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("D01_REQUIRED_" + key);
        }
        return value;
    }
}
