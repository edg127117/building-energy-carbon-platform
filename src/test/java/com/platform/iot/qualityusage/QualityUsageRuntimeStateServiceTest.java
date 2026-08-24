package com.platform.iot.qualityusage;

import com.platform.config.DataQualityProperties;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyInterval;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;
import com.platform.iot.qualityusage.QualityUsageModels.QualityLevel;
import com.platform.iot.qualityusage.QualityUsageModels.RuntimeApplyStatus;
import com.platform.iot.qualityusage.QualityUsageModels.RuntimeSnapshot;
import com.platform.iot.qualityusage.QualityUsageModels.Scenario;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.platform.iot.qualityusage.QualityUsageModels.POINT_REALTIME_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityUsageRuntimeStateServiceTest {

    private final QualityUsagePolicyRepository repository =
            mock(QualityUsagePolicyRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final DataQualityProperties dataQuality = new DataQualityProperties();
    private final QualityUsageRuntimeStateService service =
            new QualityUsageRuntimeStateService(repository, dataQuality, events);

    @Test
    void firstLoadFailureFailsClosed() {
        when(repository.currentRevision()).thenThrow(new IllegalStateException("mysql unavailable"));

        assertThat(service.refreshIfChanged(true)).isFalse();
        assertThat(service.applyStatus()).isEqualTo(RuntimeApplyStatus.REFRESH_FAILED);
        assertThatThrownBy(service::requireSnapshot)
                .isInstanceOf(QualityUsageSnapshotUnavailableException.class);
    }

    @Test
    void unchangedRevisionUsesOnlyLightweightRevisionRead() {
        RuntimeSnapshot initial = snapshot(3, Set.of(QualityLevel.Q0));
        when(repository.currentRevision()).thenReturn(3L);
        when(repository.loadRuntimeSnapshot(anyLong())).thenReturn(initial);

        assertThat(service.refreshIfChanged(true)).isTrue();
        assertThat(service.refreshIfChanged(false)).isFalse();

        verify(repository).loadRuntimeSnapshot(anyLong());
    }

    @Test
    void affectedPolicyFailureRetainsPreviouslyAppliedSnapshot() {
        RuntimeSnapshot initial = snapshot(3, Set.of(QualityLevel.Q0));
        RuntimeSnapshot replacement = snapshot(4, Set.of(QualityLevel.Q0, QualityLevel.Q1));
        when(repository.currentRevision()).thenReturn(3L, 4L);
        when(repository.loadRuntimeSnapshot(anyLong())).thenReturn(initial, replacement);
        when(repository.affectedPolicies(3, 4))
                .thenThrow(new IllegalStateException("affected query failed"));

        assertThat(service.refreshIfChanged(true)).isTrue();
        assertThat(service.refreshIfChanged(false)).isFalse();

        assertThat(service.requireSnapshot()).isSameAs(initial);
        assertThat(service.runtimeRevision()).isEqualTo(3);
        assertThat(service.applyStatus()).isEqualTo(RuntimeApplyStatus.REFRESH_FAILED);
        verify(events, never()).publishEvent(
                new QualityUsageRuntimeRefreshedEvent(3, 4, Set.of(), null));
    }

    private RuntimeSnapshot snapshot(long revision, Set<QualityLevel> levels) {
        PolicyKey key = new PolicyKey("POINT001", POINT_REALTIME_VIEW);
        return new RuntimeSnapshot(
                revision,
                Map.of(POINT_REALTIME_VIEW,
                        new Scenario(POINT_REALTIME_VIEW, "POINT_REALTIME_GATE", "ENABLED")),
                Map.of(key, List.of(new PolicyInterval(
                        "POLICY001", (int) revision, null, null, levels))));
    }
}
