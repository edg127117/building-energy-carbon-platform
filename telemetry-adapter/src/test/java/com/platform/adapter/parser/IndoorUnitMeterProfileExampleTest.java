package com.platform.adapter.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.model.StandardMetric;
import com.platform.adapter.model.TimeSource;
import com.platform.adapter.profile.JdbcProtocolProfileProvider;
import com.platform.adapter.profile.ProtocolProfileResolutionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@ActiveProfiles("test")
class IndoorUnitMeterProfileExampleTest {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void loadsActualSqlAndParsesSevenReadingsOnlyAfterExplicitEnablement() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/indoor-unit-meter-1039.example.sql"))
                .replaceAll("(?m)^--.*$", "");
        for (String statement : sql.split(";")) {
            if (statement.trim().startsWith("INSERT INTO")) jdbc.execute(statement.trim());
        }
        ObjectMapper mapper = new ObjectMapper();
        byte[] payload = """
                {"SN":"test-indoor-meter","param":{"ID255":{"M":"1039","1#":{
                "U":221.401,"I":11.246,"P":2.492,"Pf":1,"F":50.04,"EPP":536.86,"EPN":0
                }}}}
                """.getBytes(StandardCharsets.UTF_8);
        var root = mapper.readTree(payload);
        JdbcProtocolProfileProvider provider = new JdbcProtocolProfileProvider(jdbc);
        provider.refresh();
        assertThatThrownBy(() -> provider.resolve("device/raw/energy/up", root))
                .isInstanceOf(ProtocolProfileResolutionException.class);

        jdbc.update("UPDATE iot_protocol_profile SET enabled=1 WHERE profile_id='IDU_METER_1039_V1'");
        provider.refresh();
        var resolved = provider.resolve("device/raw/energy/up", root);
        var message = new JsonTelemetryAdapter(mapper).adapt(
                "device/raw/energy/up", payload, 1_785_398_400_000L,
                resolved.profile(), resolved.mappings());
        assertThat(message.profileCode()).isEqualTo("INDOOR_UNIT_METER_1039");
        assertThat(message.deviceIdentity().value()).isEqualTo("test-indoor-meter");
        assertThat(message.collectedAt()).isNull();
        assertThat(message.timeSource()).isEqualTo(TimeSource.ADAPTER_RECEIVED);
        assertThat(message.metrics()).extracting(StandardMetric::code)
                .containsExactly("VOLTAGE", "CURRENT", "POWER", "POWER_FACTOR", "FREQUENCY",
                        "POSITIVE_ENERGY", "NEGATIVE_ENERGY");
        assertThat(message.metrics()).extracting(StandardMetric::unit)
                .containsExactly("V", "A", "kW", "1", "Hz", "kWh", "kWh");
        assertThat(message.metrics().get(5).value()).isEqualByComparingTo("536.86");
        assertThat(message.metrics().get(6).value()).isEqualByComparingTo("0");

        var wrongModel = mapper.readTree("{\"SN\":\"test\",\"param\":{\"ID255\":{\"M\":\"other\"}}}");
        assertThatThrownBy(() -> provider.resolve("device/raw/energy/up", wrongModel))
                .isInstanceOf(ProtocolProfileResolutionException.class);
    }
}
