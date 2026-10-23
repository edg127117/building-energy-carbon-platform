package com.platform.iot.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.tools.D01SchemaMigration;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 本机一次性容器专用：D01 非空库升级、存量摘要和部分 DDL 故障注入。 */
public final class D01UpgradeProcess {
    private D01UpgradeProcess() { }

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("D01_UPGRADE_ISOLATED")) ||
                !System.getenv("D01_JDBC_URL").startsWith("jdbc:mysql://127.0.0.1:")) {
            throw new IllegalStateException("ISOLATED_LOOPBACK_REQUIRED");
        }
        var source = new DriverManagerDataSource(System.getenv("D01_JDBC_URL"),
                System.getenv("D01_JDBC_USER"), System.getenv("D01_JDBC_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        switch (args[0]) {
            case "init83" -> {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('iot_platform','iot_platform_archive')", Integer.class) != 0) {
                    throw new IllegalStateException("EMPTY_NEW_INSTANCE_REQUIRED");
                }
                Flyway.configure().dataSource(source).locations("classpath:db/migration/mysql")
                        .target("83").load().migrate();
                jdbc.update("UPDATE sys_user SET nickname='D01 existing account' WHERE username='admin'");
                for (String status : new String[]{"PENDING_REVIEW", "EXECUTED"}) {
                    jdbc.update("""
                            INSERT INTO sys_sensitive_change_request(request_id,operation_code,status,
                            target_type,target_id,command_json,request_sha256,impact_summary,submitted_by,
                            idempotency_key,trace_id,environment_mode)
                            SELECT ?, 'D01_REHEARSAL_ONLY', ?, 'SYNTHETIC','existing', '{}', ?,
                            'isolated existing approval',id,?,?,'TEST' FROM sys_user WHERE username='admin'
                            """, "D01-" + status, status, "a".repeat(64), "D01-" + status, "D01-" + status);
                }
                jdbc.execute("ALTER DATABASE iot_platform COLLATE utf8mb4_unicode_ci");
                System.out.println("D01_V83_NONEMPTY_READY");
            }
            case "snapshot" -> {
                long maxMenu = Long.parseLong(args[1]);
                Map<String, Object> snapshot = new TreeMap<>();
                var tables = jdbc.queryForList("SELECT table_schema,table_name FROM information_schema.tables WHERE table_schema IN ('iot_platform','iot_platform_archive') ORDER BY table_schema,table_name");
                for (var table : tables) {
                    String schema = table.get("TABLE_SCHEMA").toString(), name = table.get("TABLE_NAME").toString();
                    if (Set.of("biz_telemetry_raw_intent", "biz_telemetry_conflict_evidence", "biz_telemetry_conflict_owner",
                            "biz_telemetry_resolution", "biz_telemetry_retention_boundary", "biz_telemetry_conflict_budget",
                            "biz_telemetry_message_intent", "biz_telemetry_message_conflict", "biz_telemetry_change_task").contains(name)) {
                        continue;
                    }
                    String filter = "";
                    if (schema.equals("iot_platform")) {
                        if (name.equals("sys_menu")) filter = " WHERE id<=" + maxMenu;
                        if (name.equals("sys_role_menu")) filter = " WHERE menu_id<=" + maxMenu;
                        if (name.equals("flyway_schema_history")) filter = " WHERE version IS NULL OR CAST(version AS UNSIGNED)<=83";
                    }
                    var rows = jdbc.query("SELECT * FROM `" + schema + "`.`" + name + "`" + filter, (rs, number) -> {
                        var row = new StringBuilder();
                        for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
                            String value = rs.getString(column);
                            row.append(value == null ? "N;" : "V" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + ";");
                        }
                        return row.toString();
                    });
                    var sorted = new ArrayList<>(rows);
                    sorted.sort(String::compareTo);
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8)));
                    snapshot.put(schema + "." + name, Map.of("rows", rows.size(), "sha256", digest));
                }
                System.out.println("D01_SNAPSHOT=" + new ObjectMapper().writeValueAsString(snapshot));
            }
            case "menuMax" -> System.out.println("D01_MENU_MAX=" + jdbc.queryForObject("SELECT MAX(id) FROM sys_menu", Long.class));
            case "upgrade85" -> {
                var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration/mysql")
                        .target("85").baselineOnMigrate(false).cleanDisabled(true).load();
                if (!"84".equals(flyway.info().current().getVersion().getVersion())
                        || flyway.migrate().migrationsExecuted != 1) {
                    throw new IllegalStateException("D01_V85_EXACTLY_ONE_MIGRATION_REQUIRED");
                }
                flyway.validate();
                System.out.println("D01_V85_ISOLATED_UPGRADE_VALIDATED");
            }
            case "partial" -> {
                // 在后半段故障，验证前序 DDL 已提交而旧业务行未变化，不能靠事务或重复迁移恢复。
                jdbc.execute("CREATE TABLE biz_telemetry_message_intent(blocker INT)");
                boolean failed = false;
                try {
                    D01SchemaMigration.main(new String[]{"upgrade84"});
                } catch (RuntimeException expected) {
                    failed = true;
                }
                if (!failed || jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='biz_telemetry_raw_intent'", Integer.class) != 1 ||
                        jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='84' AND success=0", Integer.class) != 1) {
                    throw new AssertionError("PARTIAL_DDL_NOT_REPRODUCED");
                }
                System.out.println("D01_PARTIAL_DDL_PRESERVED");
            }
            default -> D01SchemaMigration.main(args);
        }
    }
}
