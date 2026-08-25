package com.platform.iot.qualityusage;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyInterval;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;
import com.platform.iot.qualityusage.QualityUsageModels.PolicySource;
import com.platform.iot.qualityusage.QualityUsageModels.QualityLevel;
import com.platform.iot.qualityusage.QualityUsageModels.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.platform.iot.qualityusage.QualityUsageModels.POINT_HISTORY_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityUsagePolicyResolverTest {
    @Test
    void defaultsToQ0OnlyWhenNoPublishedIntervalMatches() {
        QualityUsagePolicyResolver resolver = resolver(Map.of());

        assertThat(resolver.resolve("P1", POINT_HISTORY_VIEW, 60_000, 0).decision())
                .isEqualTo(Decision.ALLOW);
        var blocked = resolver.resolve("P1", POINT_HISTORY_VIEW, 60_000, 1);
        assertThat(blocked.decision()).isEqualTo(Decision.BLOCK);
        assertThat(blocked.policySource()).isEqualTo(PolicySource.SYSTEM_DEFAULT_Q0_ONLY);
        assertThat(blocked.policyVersion()).isNull();
    }

    @Test
    void distinguishesExplicitEmptySetAndIndependentQ1Q2Permission() {
        QualityUsagePolicyResolver empty = resolver(Map.of(
                new PolicyKey("P1", POINT_HISTORY_VIEW),
                List.of(new PolicyInterval("POL1", 1, null, null, Set.of()))));
        assertThat(empty.resolve("P1", POINT_HISTORY_VIEW, 120_000, 0).decision())
                .isEqualTo(Decision.BLOCK);

        QualityUsagePolicyResolver q0q2 = resolver(Map.of(
                new PolicyKey("P1", POINT_HISTORY_VIEW),
                List.of(new PolicyInterval("POL1", 2, null, null,
                        Set.of(QualityLevel.Q0, QualityLevel.Q2)))));
        assertThat(q0q2.resolve("P1", POINT_HISTORY_VIEW, 120_000, 1).decision())
                .isEqualTo(Decision.BLOCK);
        assertThat(q0q2.resolve("P1", POINT_HISTORY_VIEW, 120_000, 2).decision())
                .isEqualTo(Decision.ALLOW);
    }

    @Test
    void resolvesHalfOpenIntervalsAtMinuteBoundaries() {
        QualityUsagePolicyResolver resolver = resolver(Map.of(
                new PolicyKey("P1", POINT_HISTORY_VIEW), List.of(
                        new PolicyInterval("POL1", 1, null, 120_000L,
                                Set.of(QualityLevel.Q0)),
                        new PolicyInterval("POL1", 2, 120_000L, null,
                                Set.of(QualityLevel.Q0, QualityLevel.Q1)))));

        assertThat(resolver.resolve("P1", POINT_HISTORY_VIEW, 60_000, 1).decision())
                .isEqualTo(Decision.BLOCK);
        assertThat(resolver.resolve("P1", POINT_HISTORY_VIEW, 120_000, 1).decision())
                .isEqualTo(Decision.ALLOW);
    }

    @Test
    void failsClosedForDisabledScenarioAndRejectsUnalignedMinute() {
        QualityUsagePolicyResolver disabled = QualityUsageTestFixtures.resolver(
                7,
                Map.of(POINT_HISTORY_VIEW,
                        new Scenario(POINT_HISTORY_VIEW, "POINT_HISTORY_GATE", "DISABLED")),
                Map.of());
        assertThatThrownBy(() -> disabled.resolve(
                "P1", POINT_HISTORY_VIEW, 60_000, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QualityUsageErrors.SCENARIO_DISABLED);

        assertThatThrownBy(() -> resolver(Map.of()).resolve(
                "P1", POINT_HISTORY_VIEW, 60_001, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private QualityUsagePolicyResolver resolver(
            Map<PolicyKey, List<PolicyInterval>> policies) {
        return QualityUsageTestFixtures.resolver(7,
                Map.of(POINT_HISTORY_VIEW,
                        new Scenario(POINT_HISTORY_VIEW, "POINT_HISTORY_GATE", "ENABLED")),
                policies);
    }
}
