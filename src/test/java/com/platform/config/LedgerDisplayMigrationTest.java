package com.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H2 MySQL 模式执行真实 V121，验证明确种子与约束，不连接生产 MySQL。 */
class LedgerDisplayMigrationTest {
    @Test
    void seedsOnlyExplicitTypesAndKeepsMeterAndDaikinDisplaySemanticsConstrained() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:ledger-display-migration;MODE=MySQL;DATABASE_TO_UPPER=false")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("CREATE TABLE biz_equipment_type(type_code VARCHAR(50) PRIMARY KEY,status TINYINT NOT NULL)");
            jdbc.update("INSERT INTO biz_equipment_type(type_code,status) VALUES "
                    + "('ELECTRIC_METER_1P',1),('ELECTRIC_METER_3P',1),('IDU',1),('ODU',1),('WCP',1),"
                    + "('AHU',0),('PUMP',1),('CUSTOM_UNCONFIRMED',1)");

            jdbc.execute("CREATE TABLE sys_menu(id BIGINT PRIMARY KEY,menu_name VARCHAR(100),path VARCHAR(200))");
            jdbc.update("INSERT INTO sys_menu VALUES(1,'用能设备台账','/operations/devices/businessDevices'),(2,'监测采集设备','/operations/devices/meters')");
            String script = Files.readString(Path.of("src/env/init/V122__mysql_ledger_display_rules.sql"));
            String h2Compatible = script.replaceAll("(?m)^SET NAMES [^;]+;\\R", "")
                    .replaceAll("(?m)^USE `[^`]+`;\\R", "");
            ScriptUtils.executeSqlScript(connection,
                    new ByteArrayResource(h2Compatible.getBytes(StandardCharsets.UTF_8)));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_ledger_type_rule", Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT menu_name FROM sys_menu WHERE id=1", String.class)).isEqualTo("设备综合台账");
            assertThat(jdbc.queryForObject("SELECT menu_name FROM sys_menu WHERE id=2", String.class)).isEqualTo("监测采集设备");
            assertRule(jdbc, "ELECTRIC_METER_1P", "POWER", 0, "MAIN", "P");
            assertRule(jdbc, "ELECTRIC_METER_3P", "POWER", 0, "MAIN", "P");
            assertRule(jdbc, "IDU", "HVAC", 1, "MAIN", "roomTemp");
            assertRule(jdbc, "WCP", "HVAC", 1, null, null);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_ledger_type_rule "
                    + "WHERE type_code IN ('PUMP','CUSTOM_UNCONFIRMED','AHU')", Integer.class)).isZero();

            assertThatThrownBy(() -> jdbc.update("INSERT INTO biz_ledger_type_rule(type_code,professional_group,"
                    + "operation_applicable,point_component,point_suffix,evidence,revision) "
                    + "VALUES('BAD_GROUP','GUESS',1,NULL,NULL,'x',1)"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO biz_ledger_type_rule(type_code,professional_group,"
                    + "operation_applicable,point_component,point_suffix,evidence,revision) "
                    + "VALUES('BAD_PAIR','HVAC',1,'MAIN',NULL,'x',1)"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO biz_ledger_type_rule(type_code,professional_group,"
                    + "operation_applicable,point_component,point_suffix,evidence,revision) "
                    + "VALUES('BAD_REVISION','HVAC',1,NULL,NULL,'x',0)"))
                    .isInstanceOf(RuntimeException.class);
            jdbc.update("INSERT INTO biz_ledger_equipment_classification(equipment_id,professional_group,evidence,revision,updated_by) "
                    + "VALUES('E_REVOKED',NULL,'撤销覆盖',1,9)");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO biz_ledger_equipment_classification(equipment_id,"
                    + "professional_group,evidence,revision,updated_by) VALUES('E_BAD','GUESS','x',1,9)"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO biz_ledger_config_audit(config_kind,target_id,changed_by,"
                    + "before_json,after_json) VALUES('TYPE_RULE','T',NULL,'{}','{}')"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private static void assertRule(JdbcTemplate jdbc, String typeCode, String group, int operationApplicable,
                                   String component, String suffix) {
        var row = jdbc.queryForMap("SELECT professional_group,operation_applicable,point_component,point_suffix "
                + "FROM biz_ledger_type_rule WHERE type_code=?", typeCode);
        assertThat(row.get("professional_group")).isEqualTo(group);
        assertThat(((Number) row.get("operation_applicable")).intValue()).isEqualTo(operationApplicable);
        assertThat(row.get("point_component")).isEqualTo(component);
        assertThat(row.get("point_suffix")).isEqualTo(suffix);
    }
}
