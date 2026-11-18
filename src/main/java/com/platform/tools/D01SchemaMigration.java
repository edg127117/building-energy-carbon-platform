package com.platform.tools;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.List;
import java.util.Set;

/** 独立维护进程，只运行 Flyway，不启动采集、审核、重算或定时任务。 */
public final class D01SchemaMigration {
    private static final int LATEST_ALIGNMENT_SCHEMA = 123;
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
        runForDatabase(action, environment, "iot_platform");
    }

    /** 共享入口固定库名；独立入口先绑定自身运行库，再复用同一逐步迁移与结构校验。 */
    static void runForDatabase(String action, Map<String, String> environment, String expectedDatabase) {
        if (expectedDatabase == null || !expectedDatabase.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("D01_EXPECTED_DATABASE_REQUIRED");
        }
        String target = target(action);
        boolean upgrade = action.startsWith("upgrade");
        int targetVersion = Integer.parseInt(target);
        String expected = upgrade ? upgradePredecessor(targetVersion, environment) : target;
        String url = required(environment, "D01_JDBC_URL");
        if (!url.startsWith("jdbc:mysql://") || url.matches("(?is).*[?&](user|password)=.*")) {
            throw new IllegalArgumentException("D01_JDBC_URL_WITHOUT_CREDENTIALS_REQUIRED");
        }
        String user = required(environment, "D01_JDBC_USER");
        String password = required(environment, "D01_JDBC_PASSWORD");
        var source = new DriverManagerDataSource(url, user, password);
        var jdbc = new JdbcTemplate(source);
        if (!expectedDatabase.equals(jdbc.queryForObject("SELECT DATABASE()", String.class))) {
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
            requireSingleTargetPending(target, java.util.Arrays.stream(pending)
                    .map(migration -> migration.getVersion().getVersion()).toList());
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
        if (targetVersion >= 93 && jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                AND table_name='biz_historical_metering_scope' AND engine='InnoDB'
                AND table_collation='utf8mb4_unicode_ci'
                """, Integer.class) != 1) {
            throw new IllegalStateException("D01_V93_HISTORICAL_METERING_TABLE_INCOMPLETE");
        }
        if (targetVersion >= 94) {
            // 保留旧对齐版本的结构约束，新迁移由 Flyway 校验脚本与执行历史。
            SharedAlignmentSchemaContract.validate(jdbc, Math.min(targetVersion, LATEST_ALIGNMENT_SCHEMA));
        }
        System.out.println("D01_SCHEMA_VALIDATED=" + target);
    }

    private static String upgradePredecessor(int version, Map<String, String> environment) {
        if (version <= LATEST_ALIGNMENT_SCHEMA) {
            return predecessor(version);
        }
        // 新版本的前驱取自已核验的发布清单，SQL 编号可以跳号。
        String previous = required(environment, "D01_EXPECTED_PREDECESSOR");
        if (!previous.matches("[1-9][0-9]{2,8}") || Integer.parseInt(previous) < 123
                || Integer.parseInt(previous) >= version) {
            throw new IllegalArgumentException("D01_SCHEMA_PREDECESSOR_INVALID");
        }
        return previous;
    }

    static String predecessor(int version) {
        return switch (version) {
            case 93 -> "88";
            case 94 -> "93";
            case 110 -> "107";
            default -> Integer.toString(version - 1);
        };
    }

    static void requireSingleTargetPending(String target, List<String> pendingVersions) {
        if (pendingVersions.size() != 1 || !target.equals(pendingVersions.getFirst())) {
            throw new IllegalStateException("93".equals(target)
                    ? "D01_V93_SINGLE_MIGRATION_REQUIRED" : "D01_SINGLE_TARGET_MIGRATION_REQUIRED");
        }
    }

    static String target(String action) {
        if (action.matches("(?:validate|upgrade)[1-9][0-9]{2,8}")) {
            String version = action.replaceFirst("^(?:validate|upgrade)", "");
            if (Integer.parseInt(version) > LATEST_ALIGNMENT_SCHEMA) {
                return version;
            }
        }
        return switch (action) {
            case "validate83" -> "83";
            case "validate84", "upgrade84" -> "84";
            case "validate85", "upgrade85" -> "85";
            case "validate86", "upgrade86" -> "86";
            case "validate87", "upgrade87" -> "87";
            case "validate88", "upgrade88" -> "88";
            case "validate93", "upgrade93" -> "93";
            case "validate94", "upgrade94" -> "94";
            case "validate95", "upgrade95" -> "95";
            case "validate96", "upgrade96" -> "96";
            case "validate97", "upgrade97" -> "97";
            case "validate98", "upgrade98" -> "98";
            case "validate99", "upgrade99" -> "99";
            case "validate100", "upgrade100" -> "100";
            case "validate101", "upgrade101" -> "101";
            case "validate102", "upgrade102" -> "102";
            case "validate103", "upgrade103" -> "103";
            case "validate104", "upgrade104" -> "104";
            case "validate105", "upgrade105" -> "105";
            case "validate106", "upgrade106" -> "106";
            case "validate107", "upgrade107" -> "107";
            case "validate110", "upgrade110" -> "110";
            case "validate111", "upgrade111" -> "111";
            case "validate112", "upgrade112" -> "112";
            case "validate113", "upgrade113" -> "113";
            case "validate114", "upgrade114" -> "114";
            case "validate115", "upgrade115" -> "115";
            case "validate116", "upgrade116" -> "116";
            case "validate117", "upgrade117" -> "117";
            case "validate118", "upgrade118" -> "118";
            case "validate119", "upgrade119" -> "119";
            case "validate120", "upgrade120" -> "120";
            case "validate121", "upgrade121" -> "121";
            case "validate122", "upgrade122" -> "122";
            case "validate123", "upgrade123" -> "123";
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
