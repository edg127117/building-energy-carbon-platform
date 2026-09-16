package com.platform.config;

import com.platform.hvac.model.entity.BizPointNamingRule;
import com.platform.hvac.service.PointCodeNamingValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OutdoorUnitNamingMigrationContractTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PointCodeNamingValidator namingValidator;

    @Test
    void addsNamingRuleWithoutReplacingExistingOutdoorType() throws Exception {
        String sql = Files.readString(Path.of("src/env/init/V54__mysql_outdoor_unit_type.sql"))
                .replaceAll("(?m)^--.*$", "");
        var existingTypes = jdbc.queryForList("SELECT * FROM biz_equipment_type");
        assertThat(sql).doesNotContain("INSERT INTO `biz_equipment_type`",
                "INSERT INTO `biz_device_product`", "INSERT INTO `biz_equipment`");
        for (String statement : sql.split(";")) {
            if (statement.trim().startsWith("INSERT INTO")) jdbc.execute(statement.trim());
        }
        assertThat(jdbc.queryForList("SELECT * FROM biz_equipment_type")).isEqualTo(existingTypes);
        BizPointNamingRule rule = new BizPointNamingRule();
        rule.setCodeTemplate(jdbc.queryForObject(
                "SELECT code_template FROM biz_point_naming_rule WHERE rule_id='RULE_ODU_MAIN'",
                String.class));
        rule.setComponentCode("MAIN");
        assertThat(namingValidator.matches(rule, "ODU1_EPP")).isTrue();
        assertThat(namingValidator.matches(rule, "IDU1_EPP")).isFalse();
    }
}
