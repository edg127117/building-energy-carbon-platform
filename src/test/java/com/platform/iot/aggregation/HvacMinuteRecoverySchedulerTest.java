package com.platform.iot.aggregation;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class HvacMinuteRecoverySchedulerTest {

    @Test
    void delegatesStartupAndPeriodicRecoveryToAggregationService() {
        HvacMinuteAggregationService aggregationService =
                mock(HvacMinuteAggregationService.class);
        HvacMinuteRecoveryScheduler scheduler =
                new HvacMinuteRecoveryScheduler(aggregationService);

        scheduler.recoverOnApplicationReady();
        scheduler.recoverPeriodically();

        verify(aggregationService, times(2)).recoverRecentMinutes(anyLong());
    }
}
