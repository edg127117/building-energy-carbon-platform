package com.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.protocol.ProtocolPreviewService;
import com.platform.iot.protocol.api.ProtocolContracts.Configuration;
import com.platform.iot.protocol.api.ProtocolContracts.PreviewRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OutdoorUnitMeter339MigrationContractTest {
    private static final Path MIGRATION = Path.of("src", "env", "init",
            "V57__mysql_outdoor_unit_meter_339.sql");
    private static final String SAMPLE = """
            {"SN":"test-only-outdoor-meter","param":{"ID1":{"M":"339","PT":1,"CT":1,
              "A1":{"U":219.993,"I":0.000},"B1":{"U":0.000,"I":0.000},
              "C1":{"U":0.000,"I":0.000},
              "T1":{"P":0.000,"Pf":1.000,"F":49.99,"EPP":0.00,"EPN":0.00}}}}
            """;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ProtocolPreviewService previewService;

    @Test
    void reconstructsProductAndParsesConfirmedElevenPointPayload() throws Exception {
        jdbc.update("""
                INSERT INTO biz_equipment_type
                (type_code,type_name,asset_code_prefix,equip_category,standard_source,status)
                VALUES ('ODU','空调外机','ODU','OUTDOOR_UNIT','DAIKIN_READONLY_V2',1)
                """);
        executeInserts(MIGRATION);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_product_point_template
                WHERE product_id='PRODUCT_ODU_METER_339' AND required_flag=1 AND status=1
                """, Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForList("""
                SELECT metric_code,unit FROM biz_product_point_template
                WHERE product_id='PRODUCT_ODU_METER_339' ORDER BY sort_order
                """))
                .extracting(row -> row.get("METRIC_CODE") + ":" + row.get("UNIT"))
                .containsExactly("VOLTAGE_A:V", "VOLTAGE_B:V", "VOLTAGE_C:V",
                        "CURRENT_A:A", "CURRENT_B:A", "CURRENT_C:A", "POWER:kW",
                        "POWER_FACTOR:1", "FREQUENCY:Hz", "POSITIVE_ENERGY:kWh",
                        "NEGATIVE_ENERGY:kWh");

        String stored = jdbc.queryForObject("""
                SELECT configuration_json FROM biz_protocol_draft
                WHERE draft_id='PROTOCOL_ODU_METER_339_V1'
                """, String.class);
        Configuration configuration = mapper.readValue(stored, Configuration.class);
        assertThat(configuration.sourceTopic()).isEqualTo("device/raw/energy/up");
        assertThat(configuration.identityPath()).isEqualTo("/SN");
        assertThat(configuration.discriminatorPath()).isEqualTo("/param/ID1/M");
        assertThat(configuration.discriminatorValue()).isEqualTo("339");
        assertThat(configuration.mappings()).allSatisfy(mapping -> {
            assertThat(mapping.scale()).isEqualByComparingTo("1");
            assertThat(mapping.offset()).isEqualByComparingTo("0");
        });

        var preview = previewService.preview(
                new PreviewRequest(configuration, SAMPLE, 1_789_392_343_097L),
                Set.of("PLATFORM_ADMIN"));
        assertThat(preview.success()).isTrue();
        Map<String, String> expectedValues = new LinkedHashMap<>();
        expectedValues.put("VOLTAGE_A", "219.993");
        expectedValues.put("VOLTAGE_B", "0");
        expectedValues.put("VOLTAGE_C", "0");
        expectedValues.put("CURRENT_A", "0");
        expectedValues.put("CURRENT_B", "0");
        expectedValues.put("CURRENT_C", "0");
        expectedValues.put("POWER", "0");
        expectedValues.put("POWER_FACTOR", "1");
        expectedValues.put("FREQUENCY", "49.99");
        expectedValues.put("POSITIVE_ENERGY", "0");
        expectedValues.put("NEGATIVE_ENERGY", "0");
        assertThat(preview.metrics()).hasSize(11)
                .allSatisfy(metric -> assertThat(new BigDecimal(metric.value()))
                        .isEqualByComparingTo(expectedValues.get(metric.metricCode())));
        assertThat(preview.metrics()).extracting(metric -> metric.metricCode())
                .containsExactlyElementsOf(expectedValues.keySet());
    }

    private void executeInserts(Path path) throws Exception {
        String sql = Files.readString(path).replaceAll("(?m)^--.*$", "");
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (trimmed.startsWith("INSERT INTO")) jdbc.execute(trimmed);
        }
    }
}
