package com.platform.iot.reliability;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.platform.iot.reliability.mapper.MqttFailureAggregateMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptFailureMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TELEMETRY_MYSQL_IT_URL", matches = ".+")
class TelemetryReceiptRetentionMysqlIntegrationTest {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-08-26T01:00:00Z");

    @Test
    void migratesFromV22AndContinuouslyCleansTwentyThousandRowsPerRound()
            throws Exception {
        String url = System.getenv("TELEMETRY_MYSQL_IT_URL");
        assertThat(System.getenv("TELEMETRY_MYSQL_IT_ISOLATED"))
                .as("集成测试必须由调用方显式确认使用一次性隔离 MySQL")
                .isEqualTo("true");
        assertThat(url).as("历史迁移固定使用项目数据库名")
                .contains("/iot_platform");
        DataSource dataSource = new DriverManagerDataSource(
                url,
                System.getenv().getOrDefault("TELEMETRY_MYSQL_IT_USER", "root"),
                System.getenv().getOrDefault("TELEMETRY_MYSQL_IT_PASSWORD", "test-root"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                Integer.class)).as("隔离库必须为空").isZero();

        Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init")
                .target("22").load().migrate();
        assertThat(columnExists(jdbc, "biz_telemetry_receipt", "application_ack_puback_state"))
                .isTrue();

        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/env/init").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(columnExists(jdbc, "biz_telemetry_receipt", "application_ack_puback_state"))
                .isFalse();
        assertThat(indexExists(jdbc, "biz_telemetry_receipt", "idx_receipt_cleanup"))
                .isTrue();
        assertThat(indexExists(jdbc, "biz_telemetry_receipt_failure",
                "idx_receipt_failure_occurred")).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        insertIdentity(jdbc);
        insertReceipts(jdbc, 20_005);
        insertReceipt(jdbc, "PROTECTED", NOW.minusSeconds(25 * 3600));
        insertReceipt(jdbc, "FAILURE-OLD", NOW.minusSeconds(181L * 86400));
        insertFailure(jdbc, "FAILURE-RECENT", "PROTECTED", NOW.minusSeconds(3600));
        insertFailure(jdbc, "FAILURE-OLD", "FAILURE-OLD",
                NOW.minusSeconds(181L * 86400));

        List<Map<String, Object>> explain = jdbc.queryForList("""
                EXPLAIN DELETE FROM biz_telemetry_receipt
                WHERE persisted_at < ?
                  AND NOT EXISTS (
                    SELECT 1 FROM biz_telemetry_receipt_failure f
                    WHERE f.canonical_message_id =
                          biz_telemetry_receipt.canonical_message_id
                  )
                ORDER BY persisted_at, canonical_message_id
                LIMIT 2000
                """, Timestamp.from(NOW.minusSeconds(24 * 3600)));
        assertThat(explain).anySatisfy(row -> assertThat(row.get("key"))
                .isEqualTo("idx_receipt_cleanup"));

        try (SqlSession session = sqlSessionFactory(dataSource).openSession(true)) {
            TelemetryReceiptRetentionJob job = new TelemetryReceiptRetentionJob(
                    session.getMapper(TelemetryReceiptMapper.class),
                    session.getMapper(TelemetryReceiptFailureMapper.class),
                    session.getMapper(MqttFailureAggregateMapper.class),
                    new SimpleMeterRegistry(), 24, 180, 2000, 10, 30_000,
                    Clock.fixed(NOW, PROJECT_ZONE), System::nanoTime);

            job.cleanup();
            assertThat(countReceiptsLike(jdbc, "NORMAL-%")).isEqualTo(5);
            assertThat(countReceipt(jdbc, "PROTECTED")).isEqualTo(1);

            job.cleanup();
            assertThat(countReceiptsLike(jdbc, "NORMAL-%")).isZero();
            assertThat(countReceipt(jdbc, "PROTECTED")).isEqualTo(1);
            assertThat(countReceipt(jdbc, "FAILURE-OLD")).isZero();
            assertThat(countFailure(jdbc, "FAILURE-RECENT")).isEqualTo(1);
            assertThat(countFailure(jdbc, "FAILURE-OLD")).isZero();
        }
    }

    private SqlSessionFactory sqlSessionFactory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
                "mysql-retention-it", new JdbcTransactionFactory(), dataSource));
        GlobalConfigUtils.setGlobalConfig(configuration,
                new GlobalConfig()
                        .setDbConfig(new GlobalConfig.DbConfig())
                        .setSqlInjector(new DefaultSqlInjector()));
        configuration.addMapper(TelemetryReceiptMapper.class);
        configuration.addMapper(TelemetryReceiptFailureMapper.class);
        configuration.addMapper(MqttFailureAggregateMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private void insertReceipts(JdbcTemplate jdbc, int count) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            PreparedStatement statement = connection.prepareStatement(receiptInsertSql());
            for (int index = 0; index < count; index++) {
                bindReceipt(statement, "NORMAL-" + index,
                        NOW.minusSeconds(25 * 3600L + index));
                statement.addBatch();
                if ((index + 1) % 1000 == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
            return null;
        });
    }

    private void insertIdentity(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO biz_device_identity
                  (identity_id,identity_type,identity_value,equip_id,building_id,
                   expected_profile_code,status)
                VALUES ('I1','SERIAL_NO','RETENTION-IT-DEVICE',
                        'EQUIP_AHU_B1','BLD001','P1',1)
                """);
    }

    private void insertReceipt(JdbcTemplate jdbc, String id, Instant persistedAt) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(receiptInsertSql());
            bindReceipt(statement, id, persistedAt);
            return statement;
        });
    }

    private String receiptInsertSql() {
        return """
                INSERT INTO biz_telemetry_receipt
                  (canonical_message_id,identity_id,building_id,equip_id,profile_code,
                   adapter_received_at,first_platform_received_at,last_platform_received_at,
                   persisted_at,id_source,time_source,dedup_mode,payload_hash,
                   configured_ack_mode,actual_ack_mode,receipt_status,result_code,
                   metric_count,attempt_count)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
    }

    private void bindReceipt(PreparedStatement statement, String id, Instant persistedAt)
            throws java.sql.SQLException {
        Timestamp time = Timestamp.from(persistedAt);
        statement.setString(1, id);
        statement.setString(2, "I1");
        statement.setString(3, "BLD001");
        statement.setString(4, "EQUIP_AHU_B1");
        statement.setString(5, "P1");
        statement.setTimestamp(6, time);
        statement.setTimestamp(7, time);
        statement.setTimestamp(8, time);
        statement.setTimestamp(9, time);
        statement.setString(10, "ADAPTER_GENERATED");
        statement.setString(11, "ADAPTER_RECEIVED");
        statement.setString(12, "NONE");
        statement.setString(13, "0".repeat(64));
        statement.setString(14, "EVIDENCE_ONLY");
        statement.setString(15, "EVIDENCE_ONLY");
        statement.setString(16, "PLATFORM_PERSISTED");
        statement.setString(17, "PLATFORM_PERSISTED");
        statement.setInt(18, 5);
        statement.setInt(19, 1);
    }

    private void insertFailure(
            JdbcTemplate jdbc, String failureId, String canonicalId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO biz_telemetry_receipt_failure
                  (failure_id,canonical_message_id,building_id,failure_stage,
                   failure_code,safe_detail,occurred_at)
                VALUES (?,?,?,'APPLICATION_ACK','APPLICATION_ACK_PUBLISH_FAILED',?,?)
                """, failureId, canonicalId, "BLD001", "safe-test-detail",
                Timestamp.from(occurredAt));
    }

    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, Integer.class, table, column) == 1;
    }

    private boolean indexExists(JdbcTemplate jdbc, String table, String index) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                """, Integer.class, table, index) > 0;
    }

    private int countReceiptsLike(JdbcTemplate jdbc, String pattern) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_telemetry_receipt
                WHERE canonical_message_id LIKE ?
                """, Integer.class, pattern);
    }

    private int countReceipt(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_telemetry_receipt
                WHERE canonical_message_id=?
                """, Integer.class, id);
    }

    private int countFailure(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_telemetry_receipt_failure
                WHERE failure_id=?
                """, Integer.class, id);
    }
}
