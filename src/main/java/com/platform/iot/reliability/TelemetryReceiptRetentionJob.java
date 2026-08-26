package com.platform.iot.reliability;

import com.platform.iot.reliability.mapper.MqttFailureAggregateMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptFailureMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

@Component
/**
 * 持续清理 V2 热回执和异常证据。
 *
 * <p>24 小时到期的普通成功回执可以直接删除；关联异常的回执和异常明细保留
 * 180 天。单轮共享批次数和时间预算，避免清理任务长期占用 MySQL。</p>
 */
public class TelemetryReceiptRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(TelemetryReceiptRetentionJob.class);
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final TelemetryReceiptMapper receiptMapper;
    private final TelemetryReceiptFailureMapper failureMapper;
    private final MqttFailureAggregateMapper mqttFailureMapper;
    private final MeterRegistry meterRegistry;
    private final int receiptHours;
    private final int failureDays;
    private final int batchSize;
    private final int maxBatches;
    private final long maxRunNanos;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final AtomicLong oldestExpiredLagSeconds = new AtomicLong();
    private final AtomicLong receiptRows = new AtomicLong();
    private final AtomicLong failureRows = new AtomicLong();

    @Autowired
    public TelemetryReceiptRetentionJob(
            TelemetryReceiptMapper receiptMapper,
            TelemetryReceiptFailureMapper failureMapper,
            MqttFailureAggregateMapper mqttFailureMapper,
            MeterRegistry meterRegistry,
            @Value("${telemetry-reliability.retention.receipt-hours:24}") int receiptHours,
            @Value("${telemetry-reliability.retention.failure-days:180}") int failureDays,
            @Value("${telemetry-reliability.retention.batch-size:2000}") int batchSize,
            @Value("${telemetry-reliability.retention.max-batches:10}") int maxBatches,
            @Value("${telemetry-reliability.retention.max-run-millis:30000}")
            long maxRunMillis) {
        this(receiptMapper, failureMapper, mqttFailureMapper, meterRegistry,
                receiptHours, failureDays, batchSize, maxBatches, maxRunMillis,
                Clock.system(PROJECT_ZONE), System::nanoTime);
    }

    TelemetryReceiptRetentionJob(
            TelemetryReceiptMapper receiptMapper,
            TelemetryReceiptFailureMapper failureMapper,
            MqttFailureAggregateMapper mqttFailureMapper,
            MeterRegistry meterRegistry,
            int receiptHours,
            int failureDays,
            int batchSize,
            int maxBatches,
            long maxRunMillis,
            Clock clock,
            LongSupplier nanoTime) {
        this.receiptMapper = receiptMapper;
        this.failureMapper = failureMapper;
        this.mqttFailureMapper = mqttFailureMapper;
        this.meterRegistry = meterRegistry;
        this.receiptHours = positive(receiptHours, "receipt-hours");
        this.failureDays = positive(failureDays, "failure-days");
        this.batchSize = positive(batchSize, "batch-size");
        this.maxBatches = positive(maxBatches, "max-batches");
        this.maxRunNanos = TimeUnit.MILLISECONDS.toNanos(
                positive(maxRunMillis, "max-run-millis"));
        this.clock = clock;
        this.nanoTime = nanoTime;
        registerGauges();
    }

    @Scheduled(fixedDelayString =
            "${telemetry-reliability.retention.cleanup-delay-ms:60000}")
    public void cleanup() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime receiptBefore = now.minusHours(receiptHours);
            LocalDateTime failureBefore = now.minusDays(failureDays);
            CleanupBudget budget = new CleanupBudget(
                    nanoTime.getAsLong(), maxRunNanos, maxBatches, nanoTime);

            deleteInBatches("hot_receipt", budget,
                    () -> receiptMapper.deleteExpiredWithoutFailure(receiptBefore, batchSize));
            deleteInBatches("failure_receipt", budget,
                    () -> receiptMapper.deleteExpiredWithFailure(failureBefore, batchSize));
            deleteInBatches("failure_detail", budget,
                    () -> failureMapper.deleteExpired(failureBefore, batchSize));
            deleteInBatches("mqtt_failure", budget,
                    () -> mqttFailureMapper.deleteExpired(failureBefore, batchSize));
            if (budget.canContinue()) {
                refreshGauges(receiptBefore);
            }
        } catch (RuntimeException exception) {
            meterRegistry.counter("iot.telemetry.v2.retention.cleanup_failure").increment();
            log.error("V2 回执持续清理失败: reason={}", exception.getMessage());
        } finally {
            sample.stop(meterRegistry.timer("iot.telemetry.v2.retention.cleanup_duration"));
        }
    }

    private void deleteInBatches(
            String type, CleanupBudget budget, IntSupplier deleteBatch) {
        while (budget.canContinue()) {
            int deleted = deleteBatch.getAsInt();
            budget.consumeBatch();
            meterRegistry.counter("iot.telemetry.v2.retention.deleted", "type", type)
                    .increment(deleted);
            if (deleted < batchSize) {
                return;
            }
        }
    }

    private void refreshGauges(LocalDateTime receiptBefore) {
        receiptRows.set(receiptMapper.selectCount(null));
        failureRows.set(failureMapper.selectCount(null));
        LocalDateTime oldestExpired = receiptMapper
                .selectOldestDeletablePersistedAt(receiptBefore);
        oldestExpiredLagSeconds.set(oldestExpired == null
                ? 0 : Math.max(0,
                Duration.between(oldestExpired, receiptBefore).toSeconds()));
    }

    private void registerGauges() {
        Gauge.builder("iot.telemetry.v2.retention.oldest_expired_lag_seconds",
                        oldestExpiredLagSeconds, AtomicLong::doubleValue)
                .register(meterRegistry);
        Gauge.builder("iot.telemetry.v2.retention.receipt_rows",
                        receiptRows, AtomicLong::doubleValue)
                .register(meterRegistry);
        Gauge.builder("iot.telemetry.v2.retention.failure_rows",
                        failureRows, AtomicLong::doubleValue)
                .register(meterRegistry);
    }

    private int positive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
        return value;
    }

    private long positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
        return value;
    }

    private static final class CleanupBudget {
        private final long startedAt;
        private final long maxRunNanos;
        private final int maxBatches;
        private final LongSupplier nanoTime;
        private int batches;

        private CleanupBudget(
                long startedAt,
                long maxRunNanos,
                int maxBatches,
                LongSupplier nanoTime) {
            this.startedAt = startedAt;
            this.maxRunNanos = maxRunNanos;
            this.maxBatches = maxBatches;
            this.nanoTime = nanoTime;
        }

        private boolean canContinue() {
            return batches < maxBatches
                    && nanoTime.getAsLong() - startedAt < maxRunNanos;
        }

        private void consumeBatch() {
            batches++;
        }
    }
}
