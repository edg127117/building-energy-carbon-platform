package com.platform.config;

import com.platform.energy.activity.EnergyActivityDataReader.Cursor;
import com.platform.energy.activity.TdengineEnergyActivityDataReader;
import com.platform.energy.period.EnergyPeriodModels.NumericResult;
import com.platform.energy.period.TdengineEnergyPeriodValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 专用一次性 TDengine 3.2.3 验证活动读取和周期数值幂等写入。 */
@EnabledIfEnvironmentVariable(named = "ENERGY_LOOP7_TDENGINE_IT_URL", matches = ".+")
class EnergyLoop7TdengineIntegrationTest {

    @Test
    void initializesSchemaAndRunsSeekReadAndIdempotentPeriodWrite() {
        String database = System.getenv().getOrDefault(
                "ENERGY_LOOP7_TDENGINE_IT_DATABASE", "energy_loop7_it");
        assertThat(System.getenv("ENERGY_LOOP7_TDENGINE_IT_ISOLATED"))
                .as("集成测试必须由调用方显式确认使用一次性隔离 TDengine")
                .isEqualTo("true");
        assertThat(database).matches("energy_loop7_it[a-z0-9_]*");
        var dataSource = new DriverManagerDataSource(
                System.getenv("ENERGY_LOOP7_TDENGINE_IT_URL"),
                System.getenv().getOrDefault("ENERGY_LOOP7_TDENGINE_IT_USER", "root"),
                System.getenv().getOrDefault("ENERGY_LOOP7_TDENGINE_IT_PASSWORD", "taosdata"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase(database);
        TdengineConfig config = new TdengineConfig(properties);

        jdbc.execute("DROP DATABASE IF EXISTS " + database);
        try {
            jdbc.execute("CREATE DATABASE " + database + " KEEP 30 DURATION 1d WAL_LEVEL 1");
            config.initializeHvacSchema(jdbc);
            config.initializeEnergyPeriodSchema(jdbc);
            insertRawEvents(jdbc, database);

            var reader = new TdengineEnergyActivityDataReader(jdbc, properties);
            long from = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
            long to = Instant.parse("2026-01-01T00:02:00Z").toEpochMilli();
            var first = reader.readRawEvents(
                    "BLD_LOOP7", Set.of("POINT_A", "POINT_B"), from, to, null, 1);
            assertThat(first.items()).extracting(value -> value.pointId())
                    .containsExactly("POINT_A");
            assertThat(first.truncated()).isTrue();
            assertThat(first.nextCursor()).isEqualTo(new Cursor(from + 60_000, "POINT_A"));
            var second = reader.readRawEvents(
                    "BLD_LOOP7", Set.of("POINT_A", "POINT_B"), from, to,
                    first.nextCursor(), 2);
            assertThat(second.items()).extracting(value -> value.pointId())
                    .containsExactly("POINT_B");
            assertThat(reader.readLatestAtOrBefore("BLD_LOOP7", "POINT_B", to).rawValue())
                    .isEqualTo(20.5d);

            var store = new TdengineEnergyPeriodValueStore(jdbc, properties);
            Instant periodStart = Instant.parse("2026-01-01T00:00:00Z");
            String resultKey = "a".repeat(64);
            store.write(result(resultKey, periodStart, "1000.125", "0.122925", 1));
            store.write(result(resultKey, periodStart, "1100.250", "0.135165", 2));

            Map<String, Object> stored = jdbc.queryForMap("""
                    SELECT native_quantity_decimal,tce_value_decimal,revision,evidence_hash
                    FROM %s.st_energy_period_result WHERE result_key='%s'
                    """.formatted(database, resultKey));
            assertThat(text(value(stored, "native_quantity_decimal"))).isEqualTo("1100.250");
            assertThat(text(value(stored, "tce_value_decimal"))).isEqualTo("0.135165");
            assertThat(((Number) value(stored, "revision")).longValue()).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM %s.st_energy_period_result WHERE result_key='%s'
                    """.formatted(database, resultKey), Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForList("""
                    EXPLAIN SELECT ts,revision FROM %s.st_energy_period_result
                    WHERE building_id='BLD_LOOP7' AND point_id='POINT_A'
                    """.formatted(database)).isEmpty()).isFalse();
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS " + database);
        }
    }

    private static void insertRawEvents(JdbcTemplate jdbc, String database) {
        long eventTime = Instant.parse("2026-01-01T00:01:00Z").toEpochMilli();
        jdbc.execute(rawInsert(database, "raw_loop7_a", "POINT_A", "POINT_A_CODE",
                eventTime, 10.5));
        jdbc.execute(rawInsert(database, "raw_loop7_b", "POINT_B", "POINT_B_CODE",
                eventTime, 20.5));
    }

    private static String rawInsert(
            String database, String child, String pointId, String pointCode,
            long eventTime, double value) {
        return "INSERT INTO " + database + "." + child + " USING " + database
                + ".st_raw_event TAGS ('" + pointId + "','" + pointCode
                + "','BLD_LOOP7',NULL,NULL,NULL,NULL,NULL,NULL,1) VALUES ("
                + eventTime + "," + (eventTime + 50) + "," + value
                + ",0,0,'LOOP7_SIMULATION','" + pointCode + "','DEVICE_SIMULATION')";
    }

    private static NumericResult result(
            String resultKey, Instant periodStart, String nativeValue, String tceValue,
            long revision) {
        return new NumericResult(resultKey, "BLD_LOOP7", "POINT_A", periodStart,
                new BigDecimal(nativeValue), "KWH", new BigDecimal(tceValue), "TCE",
                new BigDecimal("1.000000"), "DEVELOPMENT_SIMULATION",
                Long.toHexString(revision).repeat(64).substring(0, 64), revision);
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return value == null ? null : value.toString();
    }

    private static Object value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value : row.get(key.toUpperCase(Locale.ROOT));
    }
}
