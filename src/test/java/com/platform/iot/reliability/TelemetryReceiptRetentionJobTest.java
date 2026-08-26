package com.platform.iot.reliability;

import com.platform.iot.reliability.mapper.MqttFailureAggregateMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptFailureMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryReceiptRetentionJobTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-26T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void sharesTheConfiguredBatchBudgetAcrossAllCleanupPhases() {
        TelemetryReceiptMapper receiptMapper = mock(TelemetryReceiptMapper.class);
        TelemetryReceiptFailureMapper failureMapper = mock(TelemetryReceiptFailureMapper.class);
        MqttFailureAggregateMapper mqttMapper = mock(MqttFailureAggregateMapper.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(receiptMapper.deleteExpiredWithoutFailure(any(), eq(2))).thenReturn(2);
        TelemetryReceiptRetentionJob job = new TelemetryReceiptRetentionJob(
                receiptMapper, failureMapper, mqttMapper, meterRegistry,
                24, 180, 2, 3, 30_000, FIXED_CLOCK, () -> 0L);

        job.cleanup();

        verify(receiptMapper, times(3)).deleteExpiredWithoutFailure(any(), eq(2));
        verify(receiptMapper, never()).deleteExpiredWithFailure(any(), anyInt());
        verify(failureMapper, never()).deleteExpired(any(), anyInt());
        verify(mqttMapper, never()).deleteExpired(any(), anyInt());
        assertThat(meterRegistry.counter(
                "iot.telemetry.v2.retention.deleted", "type", "hot_receipt").count())
                .isEqualTo(6);
    }

    @Test
    void executesCleanupPhasesInRetentionOrderWhenBudgetAllows() {
        TelemetryReceiptMapper receiptMapper = mock(TelemetryReceiptMapper.class);
        TelemetryReceiptFailureMapper failureMapper = mock(TelemetryReceiptFailureMapper.class);
        MqttFailureAggregateMapper mqttMapper = mock(MqttFailureAggregateMapper.class);
        when(receiptMapper.deleteExpiredWithoutFailure(any(), eq(2000))).thenReturn(0);
        when(receiptMapper.deleteExpiredWithFailure(any(), eq(2000))).thenReturn(0);
        when(failureMapper.deleteExpired(any(), eq(2000))).thenReturn(0);
        when(mqttMapper.deleteExpired(any(), eq(2000))).thenReturn(0);
        TelemetryReceiptRetentionJob job = new TelemetryReceiptRetentionJob(
                receiptMapper, failureMapper, mqttMapper, new SimpleMeterRegistry(),
                24, 180, 2000, 10, 30_000, FIXED_CLOCK, () -> 0L);

        job.cleanup();

        var ordered = inOrder(receiptMapper, failureMapper, mqttMapper);
        ordered.verify(receiptMapper).deleteExpiredWithoutFailure(any(), eq(2000));
        ordered.verify(receiptMapper).deleteExpiredWithFailure(any(), eq(2000));
        ordered.verify(failureMapper).deleteExpired(any(), eq(2000));
        ordered.verify(mqttMapper).deleteExpired(any(), eq(2000));
    }

    @Test
    void stopsBeforeSecondBatchWhenTimeBudgetIsExhausted() {
        TelemetryReceiptMapper receiptMapper = mock(TelemetryReceiptMapper.class);
        TelemetryReceiptFailureMapper failureMapper = mock(TelemetryReceiptFailureMapper.class);
        MqttFailureAggregateMapper mqttMapper = mock(MqttFailureAggregateMapper.class);
        when(receiptMapper.deleteExpiredWithoutFailure(any(), eq(2))).thenReturn(2);
        long[] ticks = {0L, 0L, 20_000_000L, 20_000_000L, 20_000_000L};
        AtomicInteger index = new AtomicInteger();
        TelemetryReceiptRetentionJob job = new TelemetryReceiptRetentionJob(
                receiptMapper, failureMapper, mqttMapper, new SimpleMeterRegistry(),
                24, 180, 2, 10, 10, FIXED_CLOCK,
                () -> ticks[Math.min(index.getAndIncrement(), ticks.length - 1)]);

        job.cleanup();

        verify(receiptMapper, times(1)).deleteExpiredWithoutFailure(any(), eq(2));
        verify(receiptMapper, never()).deleteExpiredWithFailure(any(), anyInt());
    }
}
