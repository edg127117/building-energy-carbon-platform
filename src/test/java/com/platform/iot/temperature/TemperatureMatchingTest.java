package com.platform.iot.temperature;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import static com.platform.iot.temperature.TemperatureContracts.*;
import static org.assertj.core.api.Assertions.*;

/** 纯能力契约测试：第二厂家为测试替身，不代表现场协议已经接通。 */
class TemperatureMatchingTest {
    @Test void sourceSpecificRulePrecedesGlobalModelAndAmbiguityFails() {
        var context = new TemperatureAdapter.Context("B", "S", "MODEL", "1");
        Rule global = rule("global", "", "");
        Rule model = rule("model", "", "MODEL");
        Rule scoped = rule("source", "S", "");
        assertThat(TemperaturePlanService.match(List.of(global, model, scoped), context)).isEqualTo(scoped);
        assertThat(TemperaturePlanService.match(List.of(global, model), context)).isEqualTo(model);
        assertThatThrownBy(() -> TemperaturePlanService.match(List.of(global, rule("duplicate", "", "")), context))
                .hasMessageContaining("多个");
        assertThat(TemperaturePlanService.match(List.of(rule("other", "X", "")), context)).isNull();
    }

    @Test void sameRawFieldNameDoesNotSelectAnotherManufacturerAdapter() {
        TemperatureAdapter first = fake("VENDOR_A", "A_PROFILE");
        TemperatureAdapter second = fake("VENDOR_B", "B_PROFILE");
        var pending = new BizPendingDevice();
        pending.setIdentityType("VENDOR_B"); pending.setProfileCode("B_PROFILE");
        assertThat(List.of(first, second).stream().filter(a -> a.supports(pending)).toList()).containsExactly(second);
        assertThat(first.fields()).isEqualTo(second.fields());
        assertThat(first.sourceSystem()).isNotEqualTo(second.sourceSystem());
    }

    private Rule rule(String id, String scope, String model) { return new Rule(id, "ADAPTER", "B", scope, model, "T", "N", 1, true); }
    private TemperatureAdapter fake(String id, String profile) {
        return new TemperatureAdapter() {
            public String id() { return id; }
            public boolean supports(BizPendingDevice p) { return id.equals(p.getIdentityType()) && profile.equals(p.getProfileCode()); }
            public Context context(BizPendingDevice p) { return new Context("B", "S", "", "1"); }
            public Map<String, String> fields() { return Map.of("roomTemp", "室温"); }
            public String sourceSystem() { return id; }
            public String transportType() { return "MQTT"; }
        };
    }
}
