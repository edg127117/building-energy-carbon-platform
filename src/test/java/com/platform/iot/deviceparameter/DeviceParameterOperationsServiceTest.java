package com.platform.iot.deviceparameter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.RecalculationJob;
import com.platform.iot.deviceparameter.DeviceParameterModels.RecalculationStatus;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.formula.model.FormulaResultRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceParameterOperationsServiceTest {
    @Mock private DeviceParameterJdbcRepository repository;
    @Mock private DeviceParameterAuthorization authorization;
    @Mock private IndicatorMinuteRepository indicatorRepository;

    @Test
    void resumesFailedJobAndPreservesItsRetryHistory() {
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        RecalculationJob failed = new RecalculationJob("J1", "T1", "BLD001", "E1",
                "[\"I1\"]", from, from.plusHours(1), "FAILED", from, 3, "SAFE_FAILURE");
        RecalculationJob waiting = new RecalculationJob("J1", "T1", "BLD001", "E1",
                "[\"I1\"]", from, from.plusHours(1), "WAITING", from, 3, null);
        when(repository.findRecalculationJob("J1"))
                .thenReturn(Optional.of(failed), Optional.of(waiting));
        when(repository.resumeFailedRecalculationJob("J1")).thenReturn(1);
        DeviceParameterOperationsService service = new DeviceParameterOperationsService(
                repository, authorization, new ObjectMapper(), indicatorRepository);

        var result = service.resumeFailed(2L, List.of("PLATFORM_ADMIN"), "J1");

        assertThat(result.status()).isEqualTo("WAITING");
        assertThat(result.retryCount()).isEqualTo(3);
        verify(repository).updateTimelineRecalculationStatus(
                "T1", RecalculationStatus.PENDING_RECALC);
        verify(repository).audit(any(), eq("BLD001"), eq("USER"), eq(2L),
                eq("RESUME_RECALCULATION"), eq("RECALCULATION_JOB"), eq("J1"),
                eq("T1"), eq("FAILED"), eq("WAITING"), eq("SUCCESS"),
                eq(null), eq(null), eq(null));
    }

    @Test
    void readsFormulaResultByKnowledgeTimeAndChecksItsPersistedBuilding() {
        FormulaResultRevision revision = new FormulaResultRevision(
                "R1", "A1", "I1", "WCR_COP", "BLD001", "G1", "E1",
                1000L, 4.5, 0, "V1", "[{\"versionId\":\"P1\"}]", 2000L);
        when(indicatorRepository.findResultRevisionAt("I1", 1000L, 2500L))
                .thenReturn(Optional.of(revision));
        DeviceParameterOperationsService service = new DeviceParameterOperationsService(
                repository, authorization, new ObjectMapper(), indicatorRepository);

        var result = service.formulaResultAt(
                9L, List.of("BUILDING_OWNER"), "I1", 1000L, 2500L);

        assertThat(result.resultRevisionId()).isEqualTo("R1");
        assertThat(result.parameterEvidenceJson()).contains("P1");
        verify(authorization).checkBuilding(9L, List.of("BUILDING_OWNER"), "BLD001");
    }
}
