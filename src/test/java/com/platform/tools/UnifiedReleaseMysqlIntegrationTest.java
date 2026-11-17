package com.platform.tools;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 显式的一次性 MySQL 演练，只核验目标库隔离及新增升级路径，不连接服务器或重做历史业务计算。 */
@EnabledIfEnvironmentVariable(named = "UNIFIED_RELEASE_MYSQL_ISOLATED", matches = "true")
class UnifiedReleaseMysqlIntegrationTest {
    @Test
    void upgradesDefault121AndIndependent120WithoutCrossDatabaseWritesOrHistoryRepair() throws Exception {
        String url = System.getenv("UNIFIED_RELEASE_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/iot_platform\\?.+");
        String password = System.getenv("UNIFIED_RELEASE_MYSQL_PASSWORD");
        String cloneName = "release_isolation_check";
        var source = new DriverManagerDataSource(url, "release_it", password);
        var shared = new JdbcTemplate(source);
        assertThat(shared.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isZero();
        Flyway.configure().dataSource(source).locations("filesystem:src/env/init").target("120").load().migrate();
        cloneFrozenFixture(url, password, cloneName);
        String demoUrl = url.replace("/iot_platform?", "/" + cloneName + "?");
        var demo = new JdbcTemplate(new DriverManagerDataSource(demoUrl, "release_it", password));
        var demoHistory = history(demo);
        Flyway.configure().dataSource(source).locations("filesystem:src/env/init").target("121").load().migrate();
        assertThat(shared.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='121'", Integer.class)).isEqualTo(1838306479);
        var sharedHistory = history(shared);
        var sharedMenus = shared.queryForList("SELECT * FROM sys_menu ORDER BY id");
        var sharedGrants = shared.queryForList("SELECT * FROM sys_role_menu ORDER BY id");

        Map<String,String> demoEnvironment = environment(demoUrl, password);
        demoEnvironment.put("DEMO_EXPECTED_DATABASE", cloneName);
        // 普通共享维护入口不能用于独立库；错误绑定须在任何迁移之前失败。
        assertThrows(IllegalStateException.class, () -> D01SchemaMigration.run("validate120", demoEnvironment));
        assertThat(history(demo)).isEqualTo(demoHistory);
        for (String action : List.of("validate120", "upgrade121", "upgrade122", "upgrade123", "validate123")) {
            DemoSchemaMigration.run(action, demoEnvironment);
        }
        assertThat(history(shared)).isEqualTo(sharedHistory);
        assertThat(shared.queryForList("SELECT * FROM sys_menu ORDER BY id")).isEqualTo(sharedMenus);
        assertThat(shared.queryForList("SELECT * FROM sys_role_menu ORDER BY id")).isEqualTo(sharedGrants);
        assertThat(shared.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'biz_ledger%'", Integer.class)).isZero();
        assertThat(demo.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('biz_ledger_type_rule','biz_device_comm_target')", Integer.class)).isEqualTo(2);
        assertThat(history(demo).subList(0, demoHistory.size())).isEqualTo(demoHistory);
        var upgradedDemoHistory = history(demo);
        var sharedEnvironment = environment(url, password);
        for (String action : List.of("validate121", "upgrade122", "upgrade123", "validate123")) {
            D01SchemaMigration.run(action, sharedEnvironment);
        }
        assertThat(history(shared).subList(0, sharedHistory.size())).isEqualTo(sharedHistory);
        assertThat(history(demo)).isEqualTo(upgradedDemoHistory);
        assertThat(shared.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("123");
    }

    private Map<String,String> environment(String url, String password) {
        var values = new HashMap<String,String>();
        values.put("D01_JDBC_URL", url); values.put("D01_JDBC_USER", "release_it");
        values.put("D01_JDBC_PASSWORD", password); values.put("D01_WRITERS_FROZEN", "true");
        return values;
    }

    private List<Map<String,Object>> history(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT installed_rank,version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
    }

    private void cloneFrozenFixture(String url, String password, String cloneName) throws Exception {
        var endpoint = java.net.URI.create(url.substring("jdbc:".length()));
        String home = System.getenv("UNIFIED_RELEASE_MYSQL_CLIENT_DIRECTORY");
        String suffix = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        var dump = new ProcessBuilder(Path.of(home, "mysqldump" + suffix).toString(), "--host=127.0.0.1",
                "--port=" + endpoint.getPort(), "--user=release_it", "--default-character-set=utf8mb4",
                "--single-transaction", "--routines", "--triggers", "--events", "--set-gtid-purged=OFF",
                "--no-tablespaces", "iot_platform");
        dump.environment().put("MYSQL_PWD", password);
        dump.redirectError(ProcessBuilder.Redirect.INHERIT);
        var exporting = dump.start();
        byte[] sql = exporting.getInputStream().readAllBytes();
        assertThat(exporting.waitFor()).isZero();
        assertThat(new String(sql, StandardCharsets.UTF_8)).doesNotContain("USE `iot_platform`");
        var restore = new ProcessBuilder(Path.of(home, "mysql" + suffix).toString(), "--host=127.0.0.1",
                "--port=" + endpoint.getPort(), "--user=release_it", "--default-character-set=utf8mb4", cloneName);
        restore.environment().put("MYSQL_PWD", password);
        restore.redirectError(ProcessBuilder.Redirect.INHERIT);
        restore.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        var importing = restore.start();
        importing.getOutputStream().write(sql); importing.getOutputStream().close();
        assertThat(importing.waitFor()).isZero();
    }
}
