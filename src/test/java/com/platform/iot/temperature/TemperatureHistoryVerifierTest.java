package com.platform.iot.temperature;

import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.platform.iot.qualityusage.QualityUsageModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TemperatureHistoryVerifierTest {
    private final HvacRawEventRepository raw = mock(HvacRawEventRepository.class);
    private final QualityUsagePolicyResolver quality = mock(QualityUsagePolicyResolver.class);
    private final TemperatureHistoryVerifier verifier = new TemperatureHistoryVerifier(raw, quality);

    @Test void onlyMatchingPersistedObservationAllowedByHistoryPolicyIsUsable() {
        when(raw.findPointHistory("building", "equipment", "point", 120000, 120001, null, 1))
                .thenReturn(List.of(event("building")));
        when(quality.resolve("point", "POINT_HISTORY_VIEW", 120000, 1))
                .thenReturn(new Resolution(Decision.BLOCK, 1, "POINT_HISTORY_VIEW", PolicySource.SYSTEM_DEFAULT_Q0_ONLY, null, 0, "blocked"));
        assertThat(check()).isEqualTo("QUALITY_BLOCKED");
        when(quality.resolve("point", "POINT_HISTORY_VIEW", 120000, 1))
                .thenReturn(new Resolution(Decision.ALLOW, 1, "POINT_HISTORY_VIEW", PolicySource.PUBLISHED_POLICY, 1, 1, "allowed"));
        assertThat(check()).isEqualTo("VALID_SAMPLE");
    }

    @Test void missingWrongOwnershipAndUnavailableStorageCannotReportSuccess() {
        when(raw.findPointHistory("building", "equipment", "point", 120000, 120001, null, 1))
                .thenReturn(List.of()).thenReturn(List.of(event("other-building")))
                .thenThrow(new IllegalStateException("storage unavailable"));
        assertThat(check()).isEqualTo("HISTORY_UNAVAILABLE");
        assertThat(check()).isEqualTo("HISTORY_UNAVAILABLE");
        assertThat(check()).isEqualTo("HISTORY_UNAVAILABLE");
        verifyNoInteractions(quality);
    }

    private String check() { return verifier.verify("building", "equipment", "point", "DAIKIN_V2", 120000); }
    private RawTelemetryEvent event(String building) {
        return new RawTelemetryEvent("point", "point-code", "DAIKIN_V2", "alias", "device", building,
                null, "equipment", "equipment-code", "IDU", "MAIN", "roomTemp", 25.0, 120000, 120100, 1, 0, false);
    }
}
