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
        if (args.length != 1) {
            throw new IllegalArgumentException("D01_SCHEMA_ACTION_REQUIRED");
        }
        run(args[0], System.getenv());
    }

    static void run(String action, Map<String, String> environment) {
        String target = target(action);
        boolean upgrade = action.startsWith("upgrade");
        // V89–V92 未进入本包；V93 从已发布 V88 单独增加历史计量表，仍只允许一条迁移。
        String expected = upgrade ? ("93".equals(target) ? "88" : String.valueOf(Integer.parseInt(target) - 1)) : target;
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
                .target(target)
                .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).load();
        var current = flyway.info().current();
        if (current == null || !expected.equals(current.getVersion().getVersion())) {
            throw new IllegalStateException("D01_EXPECTED_SCHEMA_" + expected);
        }
        if (upgrade) {
            if (!"true".equals(environment.get("D01_WRITERS_FROZEN"))) {
                throw new IllegalStateException("D01_WRITERS_MUST_BE_FROZEN");
            }
            // 先验证计划的源版本；独立进程只执行该计划的一条迁移，不允许 repair 或重复升级。
            Flyway.configure().dataSource(source).locations("classpath:db/migration/mysql")
                    .target(expected).baselineOnMigrate(false).cleanDisabled(true).load().validate();
            var pending = flyway.info().pending();
            if ("93".equals(target) && (pending.length != 1 || !"93".equals(pending[0].getVersion().getVersion()))) {
                throw new IllegalStateException("D01_V93_SINGLE_MIGRATION_REQUIRED");
            }
            if (flyway.migrate().migrationsExecuted != 1) {
                throw new IllegalStateException("D01_EXACTLY_ONE_MIGRATION_REQUIRED");
            }
        }
        flyway.validate();
        if (!target.equals("83")) {
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
        if (Integer.parseInt(target) >= 85 && jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='biz_test_building_retirement'
                  AND REFERENCED_TABLE_NAME='building' AND DELETE_RULE='RESTRICT' AND UPDATE_RULE='RESTRICT'
                """, Integer.class) != 1) {
            throw new IllegalStateException("D01_V85_REFERENCE_INCOMPLETE");
        }
        if (Integer.parseInt(target) >= 86) {
            // 检查存储约束而非业务行数：已填报的正式库允许有数据，维护入口不发布楼宇参数。
            if (jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                    AND table_name IN ('biz_energy_reporting_scope','biz_energy_reporting_record',
                    'biz_energy_reporting_receipt','biz_energy_parameter_package_import_lock',
                    'biz_energy_parameter_package_import_receipt')
                    AND engine='InnoDB' AND table_collation='utf8mb4_unicode_ci'
                    """, Integer.class) != 5 || jdbc.queryForObject("""
                    SELECT COUNT(*) FROM biz_energy_parameter_package_import_lock
                    WHERE scope_key='GLOBAL_PARAMETER_PACKAGES'
                    """, Integer.class) != 1) {
                throw new IllegalStateException("D01_V86_STORAGE_INCOMPLETE");
            }
        }
        if (Integer.parseInt(target) >= 87 && jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_formula_version
                WHERE formula_version_id='CFV_HEAT_CO2_V1' AND algorithm_code='PURCHASED_HEAT_CO2_V1'
                  AND result_basis='GAS_MASS' AND gas_coverage='CO2_ONLY_HEAT'
                """, Integer.class) != 1) {
            throw new IllegalStateException("D01_V87_FORMULA_INCOMPLETE");
        }
        if (Integer.parseInt(target) >= 88) {
            String clause = jdbc.queryForObject("""
                    SELECT c.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS c
                    JOIN information_schema.TABLE_CONSTRAINTS t
                      ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME
                    WHERE c.CONSTRAINT_SCHEMA=DATABASE() AND c.CONSTRAINT_NAME='chk_carbon_electricity_years'
                      AND t.TABLE_NAME='biz_carbon_factor_version' AND t.ENFORCED='YES'
                    """, String.class);
            String normalized = clause == null ? "" : clause.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
            if (!normalized.contains("2021,2022") || !normalized.contains("national")
                    || !normalized.contains("input_unit_code") || !normalized.contains("effective_to")) {
                throw new IllegalStateException("D01_V88_DEFAULT_YEAR_CONSTRAINT_INCOMPLETE");
            }
        }
        if ("93".equals(target) && jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                AND table_name='biz_historical_metering_scope' AND engine='InnoDB'
                AND table_collation='utf8mb4_unicode_ci'
                """, Integer.class) != 1) {
            throw new IllegalStateException("D01_V93_HISTORICAL_METERING_TABLE_INCOMPLETE");
        }
        System.out.println("D01_SCHEMA_VALIDATED=" + target);
    }

    static String target(String action) {
        return switch (action) {
            case "validate83" -> "83";
            case "validate84", "upgrade84" -> "84";
            case "validate85", "upgrade85" -> "85";
            case "validate86", "upgrade86" -> "86";
            case "validate87", "upgrade87" -> "87";
            case "validate88", "upgrade88" -> "88";
            case "validate93", "upgrade93" -> "93";
            default -> throw new IllegalArgumentException("D01_SCHEMA_ACTION_REQUIRED");
        };
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("D01_REQUIRED_" + key);
        }
        return value;
    }
}
