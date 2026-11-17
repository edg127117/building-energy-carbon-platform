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

/** H2 MySQL 模式执行真实 V122；仅剔除 H2 不支持的会话/库选择语句。 */
class DeviceCommunicationMigrationTest {

    @Test
    void createsConstrainedCommunicationTablesAndOnlySeedsTheEngineeringDefault() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:device-communication-migration;MODE=MySQL;DATABASE_TO_UPPER=false")) {
            String script = Files.readString(Path.of("src/env/init/V123__mysql_device_communication.sql"));
            String h2Compatible = script.replaceAll("(?m)^SET NAMES [^;]+;\\R", "")
                    .replaceAll("(?m)^USE `[^`]+`;\\R", "");
            ScriptUtils.executeSqlScript(connection,
                    new ByteArrayResource(h2Compatible.getBytes(StandardCharsets.UTF_8)));
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

            var defaultPolicy = jdbc.queryForMap("SELECT period_seconds,monitored,evidence,revision "
                    + "FROM biz_device_comm_policy WHERE scope_key='DEFAULT'");
            assertThat(((Number) defaultPolicy.get("period_seconds")).intValue()).isEqualTo(60);
            assertThat(((Number) defaultPolicy.get("monitored")).intValue()).isEqualTo(1);
            assertThat(defaultPolicy.get("evidence")).isEqualTo("通讯工程默认周期60秒；产品/协议规则优先，未改变设备实际采集配置");
            assertThat(((Number) defaultPolicy.get("revision")).longValue()).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_policy", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_target", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_event", Integer.class)).isZero();

            assertThatThrownBy(() -> jdbc.update("INSERT INTO biz_device_comm_policy(scope_key,period_seconds,monitored,"
                    + "evidence,revision) VALUES('PROFILE:BAD',4,1,'x',1)"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO biz_device_comm_policy(scope_key,period_seconds,monitored,"
                    + "evidence,revision) VALUES('PROFILE:BAD_MONITORED',60,2,'x',1)"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO biz_device_comm_source(source_key,observer_id,health,"
                    + "observed_at,lease_until) VALUES('MQTT','observer','UNKNOWN',1,2)"))
                    .isInstanceOf(RuntimeException.class);

            jdbc.update("INSERT INTO biz_device_comm_policy_audit(scope_key,period_seconds,monitored,evidence,revision,"
                    + "changed_by) VALUES('DEFAULT',60,1,'first audit',2,7)");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO biz_device_comm_policy_audit(scope_key,period_seconds,"
                    + "monitored,evidence,revision,changed_by) VALUES('DEFAULT',60,1,'duplicate',2,8)"))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
