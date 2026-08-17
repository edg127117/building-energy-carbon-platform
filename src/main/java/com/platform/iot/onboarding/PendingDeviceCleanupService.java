package com.platform.iot.onboarding;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 有界删除过期待绑定样例，不接触正式设备档案和时序数据。
 *
 * <p>每轮同时受单批大小、最大批数和 JVM 执行时间约束；达到任一边界即结束，
 * 下一次调度继续处理，避免大量历史样例长期占用业务调度线程。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "device-onboarding.discovery.cleanup-enabled",
        havingValue = "true", matchIfMissing = true)
public class PendingDeviceCleanupService {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final PendingDeviceRepository repository;
    private final PendingDeviceDiscoveryProperties properties;
    private final MeterRegistry meterRegistry;

    public PendingDeviceCleanupService(
            PendingDeviceRepository repository,
            PendingDeviceDiscoveryProperties properties,
            MeterRegistry meterRegistry) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry 不能为空");
    }

    @Scheduled(cron = "${device-onboarding.discovery.cleanup-cron:0 45 3 * * ?}")
    public void cleanup() {
        cleanup(System.currentTimeMillis(), System::nanoTime);
    }

    /** 公开服务器时间入口便于普通测试验证保留截止点和有界删除。 */
    public CleanupResult cleanup(long now) {
        return cleanup(now, System::nanoTime);
    }

    CleanupResult cleanup(long now, LongSupplier nanoTime) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(now)
                        .minus(Duration.ofDays(properties.getRetentionDays())),
                PROJECT_ZONE);
        long started = nanoTime.getAsLong();
        int deleted = 0;
        int batches = 0;
        boolean fullLastBatch = false;
        String outcome = "completed";
        try {
            while (batches < properties.getCleanupMaxBatches()) {
                if (timedOut(started, nanoTime.getAsLong())) {
                    outcome = "timeout";
                    break;
                }
                int batchDeleted = repository.deleteExpired(
                        cutoff, properties.getCleanupBatchSize());
                deleted += batchDeleted;
                batches++;
                fullLastBatch = batchDeleted == properties.getCleanupBatchSize();
                if (batchDeleted < properties.getCleanupBatchSize()) {
                    break;
                }
            }
            if ("completed".equals(outcome)
                    && batches == properties.getCleanupMaxBatches()
                    && fullLastBatch) {
                outcome = "batch_limit";
            }
            meterRegistry.counter("iot.device-onboarding.pending.cleanup.runs",
                    "outcome", outcome).increment();
            meterRegistry.counter("iot.device-onboarding.pending.cleanup.deleted")
                    .increment(deleted);
            log.info("待绑定设备清理完成: outcome={}, batches={}, deleted={}, cutoff={}",
                    outcome, batches, deleted, cutoff);
            return new CleanupResult(outcome, batches, deleted);
        } catch (RuntimeException exception) {
            meterRegistry.counter("iot.device-onboarding.pending.cleanup.runs",
                    "outcome", "failed").increment();
            log.error("待绑定设备清理失败: batches={}, deleted={}, cutoff={}",
                    batches, deleted, cutoff, exception);
            throw exception;
        }
    }

    private boolean timedOut(long started, long current) {
        return current - started >= TimeUnit.MILLISECONDS.toNanos(
                properties.getCleanupTimeoutMs());
    }

    /** 单轮清理的低基数结果和实际删除数量。 */
    public record CleanupResult(String outcome, int batches, int deleted) {
    }
}
