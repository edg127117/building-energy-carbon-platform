package com.platform.iot.deviceparameter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.RecalculationJob;
import com.platform.iot.deviceparameter.formula.DeviceParameterFormulaImpactPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceParameterRecalculationServiceTest {
    @Mock private DeviceParameterJdbcRepository repository;
    @Mock private DeviceParameterFormulaImpactPort formulaPort;

    @Test
    void manuallyResumedWaitingJobCanRunEvenAfterAutomaticRetryLimit() {
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        RecalculationJob job = new RecalculationJob("J1", "T1", "BLD001", "E1",
                "[\"I1\"]", from, from.plusMinutes(1), "WAITING", from, 5, null);
        DeviceParameterRecalculationProperties properties =
                new DeviceParameterRecalculationProperties();
        properties.setMaxRetries(3);
        properties.setChunkMinutes(60);
        when(repository.listClaimableRecalculationJobs(properties.getBatchSize()))
                .thenReturn(List.of(job));
        when(repository.claimRecalculationJob("J1")).thenReturn(1);
        when(repository.findRecalculationJob("J1")).thenReturn(Optional.of(job));
        when(repository.advanceRecalculationJob(eq("J1"), eq(from),
                eq(from.plusMinutes(1)), anyBoolean())).thenReturn(1);
        DeviceParameterRecalculationService service = new DeviceParameterRecalculationService(
                repository, formulaPort, properties, new ObjectMapper());

        service.processWaitingJobs();

        verify(formulaPort).recalculateMinute(eq("BLD001"), any(Long.class), eq(List.of("I1")));
        verify(repository).advanceRecalculationJob(
                "J1", from, from.plusMinutes(1), true);
    }
}
