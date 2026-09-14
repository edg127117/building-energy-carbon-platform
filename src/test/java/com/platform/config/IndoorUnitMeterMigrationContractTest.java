package com.platform.config;

import com.platform.hvac.model.entity.BizPointNamingRule;
import com.platform.hvac.service.PointCodeNamingValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IndoorUnitMeterMigrationContractTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PointCodeNamingValidator namingValidator;

    @Test
    void seedsIndependentSevenPointTemplateWithoutEquipmentOrFormulaInputs() throws IOException {
        String sql = Files.readString(Path.of(
                "src/env/init/V44__mysql_indoor_unit_meter_template.sql"));

        assertThat(sql).contains("'IDU', '空调内机'")
                .contains("'INDOOR_UNIT_METER_1039'")
                .contains("'SN', 'ENABLED'")
                .contains("'VOLTAGE'", "'CURRENT'", "'POWER'", "'POWER_FACTOR'",
                        "'FREQUENCY'", "'POSITIVE_ENERGY'", "'NEGATIVE_ENERGY'")
                .doesNotContain("INSERT INTO `biz_equipment`")
                .doesNotContain("INSERT INTO `biz_device_identity`")
                .doesNotContain("INSERT INTO `biz_indicator`");
        assertThat(sql.split("NULL, NULL, 0, 1,")).hasSize(8);
    }

    @Test
    void executesTemplateSeedsAndProducesBindableNamingMetadataInIsolatedH2() throws IOException {
        String sql = Files.readString(Path.of(
                "src/env/init/V44__mysql_indoor_unit_meter_template.sql"))
                .replaceAll("(?m)^--.*$", "");
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (trimmed.startsWith("INSERT INTO")) {
                jdbc.execute(trimmed);
            }
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_product_point_template
                WHERE product_id='PRODUCT_IDU_METER_1039'
                  AND for_calc=0 AND min_value IS NULL AND max_value IS NULL
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForList("""
                SELECT suffix_code,unit FROM biz_product_point_template
                WHERE product_id='PRODUCT_IDU_METER_1039' ORDER BY sort_order
                """))
                .extracting(row -> row.get("SUFFIX_CODE") + ":" + row.get("UNIT"))
                .containsExactly("U:V", "I:A", "P:kW", "Pf:1", "F:Hz", "EPP:kWh", "EPN:kWh");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_equipment WHERE type_code='IDU'
                """, Integer.class)).isZero();

        BizPointNamingRule rule = new BizPointNamingRule();
        rule.setCodeTemplate(jdbc.queryForObject("""
                SELECT code_template FROM biz_point_naming_rule WHERE rule_id='RULE_IDU_MAIN'
                """, String.class));
        rule.setComponentCode("MAIN");
        assertThat(namingValidator.matches(rule, "IDU1_EPP")).isTrue();
    }
}
