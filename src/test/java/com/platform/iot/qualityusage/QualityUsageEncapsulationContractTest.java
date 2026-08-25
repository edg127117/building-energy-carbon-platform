package com.platform.iot.qualityusage;

import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsageModels.RuntimeSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class QualityUsageEncapsulationContractTest {

    @Test
    void exposesOnlyOpaqueResolutionContextToConsumerModules() {
        assertThat(Modifier.isPublic(RuntimeSnapshot.class.getModifiers())).isFalse();
        assertThat(Arrays.stream(ResolutionContext.class.getMethods())
                .map(Method::getName))
                .doesNotContain("snapshot", "policies", "scenarios");
        assertThat(Arrays.stream(QualityUsagePolicyResolver.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("runtimeSnapshot", "historySnapshot", "systemDefault");
    }
}
