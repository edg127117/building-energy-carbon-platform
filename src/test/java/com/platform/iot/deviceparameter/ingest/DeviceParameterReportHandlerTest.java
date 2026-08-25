package com.platform.iot.deviceparameter.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.deviceparameter.DeviceParameterCandidateService;
import com.platform.iot.deviceparameter.DeviceParameterErrors;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository;
import com.platform.iot.deviceparameter.DeviceParameterModels.EquipmentIdentity;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.onboarding.PendingDeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceParameterReportHandlerTest {
    @Mock private DeviceParameterJdbcRepository repository;
    @Mock private DeviceParameterCandidateService candidateService;
    @Mock private PendingDeviceRepository pendingRepository;

    @Test
    void knownDeviceIsRejectedUntilHardwareSemanticsAreExplicitlyEnabled() {
        var report = report();
        when(repository.findEquipmentByExternalIdentity("SN", "SN-1"))
                .thenReturn(Optional.of(new EquipmentIdentity(
                        "E1", "WCR1", "WCR", "BLD001", null)));
        DeviceParameterReportHandler handler = new DeviceParameterReportHandler(
                repository, candidateService, new DeviceParameterIngestProperties(),
                pendingRepository, new ObjectMapper());

        assertThatThrownBy(() -> handler.handle(report))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(DeviceParameterErrors.MAPPING_NOT_FOUND));
    }

    @Test
    void unknownDeviceOnlyCreatesBoundedPendingDiscovery() {
        var report = report();
        when(repository.findEquipmentByExternalIdentity("SN", "SN-1"))
                .thenReturn(Optional.empty());
        DeviceParameterReportHandler handler = new DeviceParameterReportHandler(
                repository, candidateService, new DeviceParameterIngestProperties(),
                pendingRepository, new ObjectMapper());

        var result = handler.handle(report);

        assertThat(result.status()).isEqualTo("PENDING_DEVICE");
        var captor = ArgumentCaptor.forClass(
                com.platform.iot.onboarding.PendingDeviceDiscovery.class);
        verify(pendingRepository).upsertDiscovery(captor.capture());
        assertThat(captor.getValue().metricsJson()).contains("ratedPower", "KW")
                .doesNotContain("60");
    }

    @Test
    void enabledReportStillRequiresIdentityProfileAndPublishedItemMapping() {
        var report = report();
        when(repository.findEquipmentByExternalIdentity("SN", "SN-1"))
                .thenReturn(Optional.of(new EquipmentIdentity(
                        "E1", "WCR1", "WCR", "BLD001", null)));
        when(repository.findExpectedProfileCode("SN", "SN-1"))
                .thenReturn(Optional.of("OTHER_PROFILE"));
        DeviceParameterIngestProperties properties = new DeviceParameterIngestProperties();
        properties.setEnabled(true);
        properties.setAllowedSemantics(new java.util.LinkedHashSet<>(List.of("FULL")));
        DeviceParameterReportHandler handler = new DeviceParameterReportHandler(
                repository, candidateService, properties, pendingRepository, new ObjectMapper());

        assertThatThrownBy(() -> handler.handle(report))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(DeviceParameterErrors.MAPPING_NOT_FOUND));
    }

    private static StandardDeviceParameterReport report() {
        LocalDateTime at = LocalDateTime.of(2026, 8, 1, 0, 0);
        return new StandardDeviceParameterReport("1", "PROFILE", 1,
                new DeviceIdentityKey("SN", "SN-1"), "REPORT-1", "FULL", at, at,
                List.of(new StandardDeviceParameterReport.Item(
                        "ratedPower", "60", "KW", null, "RATED_POWER")));
    }
}
