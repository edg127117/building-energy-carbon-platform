package com.platform.iot.onboarding;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingDeviceCleanupServiceTest {

    @Test
    void stopsAtConfiguredBatchLimitAndRecordsDeletedCount() {
        PendingDeviceRepository repository = mock(PendingDeviceRepository.class);
        PendingDeviceDiscoveryProperties properties = properties();
        properties.setCleanupBatchSize(2);
        properties.setCleanupMaxBatches(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(repository.deleteExpired(any(LocalDateTime.class), eq(2)))
                .thenReturn(2);
        PendingDeviceCleanupService service = new PendingDeviceCleanupService(
                repository, properties, registry);

        PendingDeviceCleanupService.CleanupResult result =
                service.cleanup(1_785_398_400_000L, () -> 0L);

        assertThat(result.outcome()).isEqualTo("batch_limit");
        assertThat(result.batches()).isEqualTo(2);
        assertThat(result.deleted()).isEqualTo(4);
        verify(repository, times(2)).deleteExpired(any(LocalDateTime.class), eq(2));
        assertThat(registry.get("iot.device-onboarding.pending.cleanup.deleted")
                .counter().count()).isEqualTo(4.0);
    }

    @Test
    void reportsCompletedWhenFinalAllowedBatchIsPartial() {
        PendingDeviceRepository repository = mock(PendingDeviceRepository.class);
        PendingDeviceDiscoveryProperties properties = properties();
        properties.setCleanupBatchSize(2);
        properties.setCleanupMaxBatches(2);
        when(repository.deleteExpired(any(LocalDateTime.class), eq(2)))
                .thenReturn(2, 1);
        PendingDeviceCleanupService service = new PendingDeviceCleanupService(
                repository, properties, new SimpleMeterRegistry());

        PendingDeviceCleanupService.CleanupResult result =
                service.cleanup(1_785_398_400_000L, () -> 0L);

        assertThat(result.outcome()).isEqualTo("completed");
        assertThat(result.batches()).isEqualTo(2);
        assertThat(result.deleted()).isEqualTo(3);
    }

    @Test
    void stopsBeforeNextBatchWhenTimeoutIsReached() {
        PendingDeviceRepository repository = mock(PendingDeviceRepository.class);
        PendingDeviceDiscoveryProperties properties = properties();
        properties.setCleanupTimeoutMs(10L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger calls = new AtomicInteger();
        LongSupplier time = () -> calls.getAndIncrement() == 0
                ? 0L : TimeUnit.MILLISECONDS.toNanos(10L);
        PendingDeviceCleanupService service = new PendingDeviceCleanupService(
                repository, properties, registry);

        PendingDeviceCleanupService.CleanupResult result =
                service.cleanup(1_785_398_400_000L, time);

        assertThat(result.outcome()).isEqualTo("timeout");
        assertThat(result.batches()).isZero();
        verify(repository, times(0)).deleteExpired(any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private PendingDeviceDiscoveryProperties properties() {
        PendingDeviceDiscoveryProperties properties = new PendingDeviceDiscoveryProperties();
        properties.setRetentionDays(30);
        properties.setCleanupBatchSize(2);
        properties.setCleanupMaxBatches(5);
        properties.setCleanupTimeoutMs(5_000L);
        return properties;
    }
}
