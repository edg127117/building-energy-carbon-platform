package com.platform.hvac;

import com.platform.hvac.model.entity.BizPointNamingRule;
import com.platform.hvac.service.EquipmentCodeAllocator;
import com.platform.hvac.service.PointCodeNamingValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndustrialIdentityRulesTest {

    @Test
    void allocatesAfterLargestHistoricalCodeAndNeverFillsDeletedGap() {
        EquipmentCodeAllocator allocator = new EquipmentCodeAllocator();

        assertThat(allocator.next("WCR", List.of())).isEqualTo("WCR1");
        assertThat(allocator.next("WCR", List.of(
                "WCR1", "WCR2", "WCR4", "OTHER99", "WCR0", "WCR-8")))
                .isEqualTo("WCR5");
    }

    @Test
    void validatesHandoffComponentTemplatesAndEnvironmentCodes() {
        PointCodeNamingValidator validator = new PointCodeNamingValidator();

        assertThat(validator.matches(rule("WCR", "MAIN", "WCR[n]"), "WCR1_TWin")).isTrue();
        assertThat(validator.matches(rule("WCR", "Pc", "WCR[n]_Pc"), "WCR1_Pc_GW")).isTrue();
        assertThat(validator.matches(rule("WCR", "CT", "WCR[n]_CT"), "WCR1_CT_TWout")).isTrue();
        assertThat(validator.matches(rule("DBO", "ENV", "DBO"), "DBO")).isTrue();

        assertThat(validator.matches(rule("WCR", "Pc", "WCR[n]_Pc"), "PUMP1_Flow")).isFalse();
        assertThat(validator.matches(rule("DBO", "ENV", "DBO"), "DBO_TDB")).isFalse();
    }

    private BizPointNamingRule rule(String family, String component, String template) {
        BizPointNamingRule rule = new BizPointNamingRule();
        rule.setFamilyCode(family);
        rule.setComponentCode(component);
        rule.setCodeTemplate(template);
        return rule;
    }
}
