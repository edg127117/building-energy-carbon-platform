package com.platform.iot.qualityusage;

import com.platform.iot.qualityusage.QualityUsageModels.PolicyInterval;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsageModels.RuntimeSnapshot;
import com.platform.iot.qualityusage.QualityUsageModels.Scenario;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** 构造不依赖 Spring 和 MySQL 的固定质量使用策略，仅供隔离测试使用。 */
public final class QualityUsageTestFixtures {
    private QualityUsageTestFixtures() {
    }

    public static QualityUsagePolicyResolver systemDefaultResolver() {
        return resolver(0, defaultScenarios(), Map.of());
    }

    public static ResolutionContext systemDefaultContext() {
        return systemDefaultResolver().runtimeContext();
    }

    public static QualityUsagePolicyResolver resolver(
            long revision,
            Map<String, Scenario> scenarios,
            Map<PolicyKey, List<PolicyInterval>> policies) {
        RuntimeSnapshot snapshot = new RuntimeSnapshot(revision, scenarios, policies);
        QualityUsageRuntimeStateService runtimeState = mock(QualityUsageRuntimeStateService.class);
        lenient().when(runtimeState.requireSnapshot()).thenReturn(snapshot);
        lenient().when(runtimeState.loadRange(anySet(), anyString(), anyLong(), anyLong()))
                .thenReturn(snapshot);
        return new QualityUsagePolicyResolver(runtimeState);
    }

    private static Map<String, Scenario> defaultScenarios() {
        return Map.of(
                QualityUsageModels.POINT_REALTIME_VIEW,
                new Scenario(QualityUsageModels.POINT_REALTIME_VIEW,
                        "POINT_REALTIME_GATE", "ENABLED"),
                QualityUsageModels.POINT_HISTORY_VIEW,
                new Scenario(QualityUsageModels.POINT_HISTORY_VIEW,
                        "POINT_HISTORY_GATE", "ENABLED"),
                QualityUsageModels.INDICATOR_CALCULATION,
                new Scenario(QualityUsageModels.INDICATOR_CALCULATION,
                        "INDICATOR_INPUT_GATE", "ENABLED"));
    }
}
