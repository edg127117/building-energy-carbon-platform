package com.platform.iot.qualityusage;

import com.platform.config.DataQualityProperties;
import com.platform.iot.formula.HvacFormulaEngine;
import com.platform.iot.formula.IndicatorConfigProvider;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;
import com.platform.iot.temporal.HvacMinuteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Set;

import static com.platform.iot.qualityusage.QualityUsageModels.INDICATOR_CALCULATION;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityUsageIndicatorRecoveryServiceTest {

    @Test
    void changeOlderThanAutomaticWindowCreatesManualRecoveryEvidence() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(fixture.executor).execute(any(Runnable.class));
        when(fixture.minutes.findRange(anySet(), anyLong(), anyLong())).thenReturn(List.of());
        when(fixture.indicators.findAllActive()).thenReturn(List.of());
        when(fixture.engine.resolveAffectedIndicatorIds(any(), anySet())).thenReturn(Set.of());

        fixture.service.onRuntimeRefreshed(new QualityUsageRuntimeRefreshedEvent(
                8, 9,
                Set.of(new PolicyKey("POINT001", INDICATOR_CALCULATION)),
                60_000L));

        verify(fixture.recoveryTasks).recordRecoveryWindowExceeded(
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq(60_000L),
                anyLong());
    }

    @Test
    void fullQueueCreatesDurableRecoveryTaskWithoutRunningCalculation() {
        Fixture fixture = fixture();
        doThrow(new TaskRejectedException("full"))
                .when(fixture.executor).execute(any(Runnable.class));

        fixture.service.onRuntimeRefreshed(new QualityUsageRuntimeRefreshedEvent(
                8, 9,
                Set.of(new PolicyKey("POINT001", INDICATOR_CALCULATION)),
                System.currentTimeMillis()));

        verify(fixture.recoveryTasks).recordQueueOverflow(9, "INDICATOR");
    }

    private Fixture fixture() {
        HvacMinuteRepository minutes = mock(HvacMinuteRepository.class);
        IndicatorConfigProvider indicators = mock(IndicatorConfigProvider.class);
        HvacFormulaEngine engine = mock(HvacFormulaEngine.class);
        QualityUsageRecoveryTaskService recoveryTasks =
                mock(QualityUsageRecoveryTaskService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        DataQualityProperties dataQuality = new DataQualityProperties();
        QualityUsageProperties properties = new QualityUsageProperties();
        QualityUsageIndicatorRecoveryService service =
                new QualityUsageIndicatorRecoveryService(
                        minutes, indicators, engine, dataQuality,
                        properties, recoveryTasks, executor);
        return new Fixture(service, minutes, indicators, engine, recoveryTasks, executor);
    }

    private record Fixture(
            QualityUsageIndicatorRecoveryService service,
            HvacMinuteRepository minutes,
            IndicatorConfigProvider indicators,
            HvacFormulaEngine engine,
            QualityUsageRecoveryTaskService recoveryTasks,
            ThreadPoolTaskExecutor executor) {
    }
}
