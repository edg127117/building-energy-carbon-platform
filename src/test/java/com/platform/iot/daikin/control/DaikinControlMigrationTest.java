package com.platform.iot.daikin.control;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** H2 验证真实增量脚本的数据兼容；MySQL 方言、DDL 失败恢复和锁行为须另行验收。 */
class DaikinControlMigrationTest {
    @Test void upgradingKeepsUncertainOperationAndSlotAndDoesNotEnableAnyActionOrUser() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:control-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sys_backend_duty(duty_key VARCHAR(50) PRIMARY KEY,duty_name VARCHAR(100),description VARCHAR(500),status VARCHAR(20),risk_level VARCHAR(20),version INT)");
        execute(ds, "V68__mysql_daikin_control.sql");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_grant", Integer.class)).isZero();
        jdbc.update("INSERT INTO biz_daikin_control_grant VALUES ('e','i','s','v','b',1,TRUE,FALSE,1,1,0,'old evidence')");
        jdbc.update("""
                INSERT INTO biz_daikin_control_operation(operation_id,equipment_id,building_id,operator_id,source_id,
                physical_key,target_json,action,requested_value,idempotency_key,request_hash,revision,status,created_at,updated_at,trace_id)
                VALUES ('old','e','b',1,'s',?,'{}','TEMPERATURE','26','old-request',?,?,'UNKNOWN',0,0,'old-trace')
                """, "a".repeat(64), "b".repeat(64), "c".repeat(64));
        jdbc.update("INSERT INTO biz_daikin_control_slot VALUES (?, 'old')", "a".repeat(64));
        execute(ds, "V69__mysql_daikin_control_stability.sql");
        execute(ds, "V70__mysql_daikin_batch_control.sql");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch_lane", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM biz_daikin_control_operation WHERE operation_id='old'", String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_slot", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cloud_stable FROM biz_daikin_control_operation", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT approved_actions FROM biz_daikin_control_grant", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT relationship_hash FROM biz_daikin_control_grant", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample", Integer.class)).isZero();
    }

    private void execute(DriverManagerDataSource ds, String file) throws Exception {
        String sql = Files.readString(Path.of("src/env/init", file))
                .replace(" COLLATE utf8mb4_bin", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        // H2 不支持一个 ALTER 语句中多个 ADD COLUMN；仅拆分语句，保留增量列定义及默认值。
        for (String table : new String[]{"biz_daikin_control_grant", "biz_daikin_control_operation"}) {
            String prefix = "ALTER TABLE " + table;
            int start = sql.indexOf(prefix);
            if (start >= 0) {
                int end = sql.indexOf(';', start);
                String statement = sql.substring(start, end).replaceAll(",\\s*ADD COLUMN", ";\n" + prefix + " ADD COLUMN");
                sql = sql.substring(0, start) + statement + sql.substring(end);
            }
        }
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
