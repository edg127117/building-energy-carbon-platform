package com.platform.iot.dataquality;

import com.platform.config.DataQualityProperties;
import com.platform.iot.aggregation.HvacPointMinuteAggregator;
import com.platform.iot.dataquality.event.HvacLateRealEventStoredEvent;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 把自动修正窗口内的迟到真实证据升级为正式 Q0 分钟。
 *
 * <p>该服务位于原始事件接入与公式重算之间：在 point+minute 锁内回读完整真实
 * 证据并写 Q0，随后发布定向 READY，最后把该 Q0 作为插值右端点修正更早短缺口。
 * 超过自动窗口的原始证据仍保留，交由管理 API 人工重算。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "data-quality", name = "enabled", havingValue = "true")
public class LateRealMinuteCorrectionService {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final double STORED_VALUE_TOLERANCE = 1.0E-9;

    private final DataPointConfigProvider configProvider;
    private final HvacRawEventRepository rawRepository;
    private final HvacMinuteRepository minuteRepository;
    private final FillTaskRepository fillTaskRepository;
    private final InterpolationFillService interpolationFillService;
    private final ApplicationEventPublisher eventPublisher;
    private final HvacPointMinuteAggregator pointMinuteAggregator;
    private final MinuteQualityLockRegistry lockRegistry;
    private final MeterRegistry meterRegistry;
    private final DataQualityProperties properties;
    private final RetryTemplate downstreamRetry;

    public LateRealMinuteCorrectionService(
            DataPointConfigProvider configProvider,
            HvacRawEventRepository rawRepository,
            HvacMinuteRepository minuteRepository,
            FillTaskRepository fillTaskRepository,
            InterpolationFillService interpolationFillService,
            ApplicationEventPublisher eventPublisher,
            HvacPointMinuteAggregator pointMinuteAggregator,
            MinuteQualityLockRegistry lockRegistry,
            MeterRegistry meterRegistry,
            DataQualityProperties properties) {
        this.configProvider = Objects.requireNonNull(
                configProvider, "configProvider");
        this.rawRepository = Objects.requireNonNull(
                rawRepository, "rawRepository");
        this.minuteRepository = Objects.requireNonNull(
                minuteRepository, "minuteRepository");
        this.fillTaskRepository = Objects.requireNonNull(
                fillTaskRepository, "fillTaskRepository");
        this.interpolationFillService = Objects.requireNonNull(
                interpolationFillService, "interpolationFillService");
        this.eventPublisher = Objects.requireNonNull(
                eventPublisher, "eventPublisher");
        this.pointMinuteAggregator = Objects.requireNonNull(
                pointMinuteAggregator, "pointMinuteAggregator");
        this.lockRegistry = Objects.requireNonNull(
                lockRegistry, "lockRegistry");
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry, "meterRegistry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.downstreamRetry = RetryTemplate.builder()
                .maxAttempts(3)
                .fixedBackoff(100)
                .retryOn(RuntimeException.class)
                .build();
    }

    /**
     * 异步执行历史分钟修正，避免 TDengine 回读和公式重算阻塞 MQTT 接入确认。
     */
    @Async("virtualThreadExecutor")
    @EventListener
    public void onLateRealEventStored(HvacLateRealEventStoredEvent event) {
        try {
            correct(event);
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                    "iot.hvac.late_real.correction_failed").increment();
            log.error("迟到真实分钟自动修正失败，等待后续人工或后台补偿: pointId={}, minute={}",
                    event == null ? null : event.pointId(),
                    event == null ? null : event.minuteStart(),
                    exception);
        }
    }

    private void correct(HvacLateRealEventStoredEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        requireText(event.pointId(), "pointId");
        requireText(event.buildingId(), "buildingId");
        if (Math.floorMod(event.minuteStart(), MINUTE_MILLIS) != 0L) {
            throw new IllegalArgumentException("minuteStart 必须对齐到分钟");
        }
        long maxAge = Math.multiplyExact(
                properties.getLateRealCorrectionHours(), HOUR_MILLIS);
        long age = event.receivedAt() - event.minuteStart();
        if (age < 0L || age > maxAge) {
            meterRegistry.counter(
                    "iot.hvac.late_real.skipped",
                    "reason", "outside_window").increment();
            log.info("迟到真实事件超过自动修正窗口，保留原始证据等待人工重算: pointId={}, minute={}, receivedAt={}",
                    event.pointId(), event.minuteStart(), event.receivedAt());
            return;
        }

        Optional<PointRuntimeConfig> point =
                configProvider.findByPointId(event.pointId());
        if (point.isEmpty()) {
            meterRegistry.counter(
                    "iot.hvac.late_real.skipped",
                    "reason", "point_not_found").increment();
            log.warn("迟到真实事件对应测点已不存在，保留原始证据: pointId={}, minute={}",
                    event.pointId(), event.minuteStart());
            return;
        }
        if (!event.buildingId().equals(point.orElseThrow().buildingId())) {
            // 测点档案可能在事件排队期间被修正；正式分钟使用当前 MySQL 配置，
            // 事件中的旧建筑值只用于诊断，不能覆盖当前测点归属。
            log.warn("迟到事件建筑与当前测点配置不一致，按当前配置修正: pointId={}, eventBuilding={}, currentBuilding={}",
                    event.pointId(), event.buildingId(),
                    point.orElseThrow().buildingId());
        }

        MinuteQualityLockRegistry.MinuteKey key =
                new MinuteQualityLockRegistry.MinuteKey(
                        event.pointId(), event.minuteStart());
        lockRegistry.withLocks(List.of(key), () -> {
            correctLocked(event, point.orElseThrow());
            return null;
        });
    }

    /**
     * 外层与 TDengine 质量写入仓储共用可重入锁；两个并发迟到通知中只有第一个
     * 先执行，后一个会基于此时的完整真实证据决定幂等退出或刷新 Q0。
     */
    private void correctLocked(
            HvacLateRealEventStoredEvent event,
            PointRuntimeConfig point) {
        Optional<RawMinuteAggregate> current =
                minuteRepository.findPointMinute(
                        event.pointId(), event.minuteStart());

        // 仓储保持现有整窗批量契约；这里只查询一次，然后按内部 pointId 过滤。
        List<RawTelemetryEvent> realEvents = rawRepository.findWindow(
                        event.minuteStart(),
                        event.minuteStart() + MINUTE_MILLIS,
                        true)
                .stream()
                .filter(raw -> event.pointId().equals(raw.pointId()))
                .toList();
        if (realEvents.isEmpty()) {
            meterRegistry.counter(
                    "iot.hvac.late_real.skipped",
                    "reason", "evidence_missing").increment();
            log.warn("迟到通知对应的真实原始证据未查到，不修改正式分钟: pointId={}, minute={}",
                    event.pointId(), event.minuteStart());
            return;
        }

        long finalizedAt = nextFinalizedAt(current, event.receivedAt());
        RawMinuteAggregate aggregate = pointMinuteAggregator.aggregate(
                point, event.minuteStart(), realEvents, finalizedAt);
        if (current.filter(row -> sameRealEvidence(row, aggregate)).isPresent()) {
            meterRegistry.counter(
                    "iot.hvac.late_real.skipped",
                    "reason", "already_real").increment();
            return;
        }
        List<MinuteQualityWriteResult> results =
                minuteRepository.saveAllWithQualityPriority(
                        List.of(aggregate), null);
        MinuteQualityWriteResult result =
                requireSingleResult(aggregate, results);
        if (!isActualWrite(result.outcome())) {
            return;
        }

        if (result.previousQuality() != null
                && result.previousQuality() > 0
                && result.previousTaskId() != null
                && !result.previousTaskId().isBlank()) {
            incrementReplacementBestEffort(result.previousTaskId());
        }

        // Q0 已经成为 TDengine 正式事实；完整分钟回读或同步公式监听短暂失败时，
        // 在当前异步任务内独立重试，不能重新执行 Q0 写入和替换计数。
        downstreamRetry.execute(context -> {
            publishReady(point, event, finalizedAt);
            return null;
        });

        // READY 默认同步返回，确保本分钟公式先修正，再把新 Q0 作为右端点回溯 Q1。
        try {
            interpolationFillService.fillFromRightEndpoints(
                    List.of(aggregate), finalizedAt);
        } catch (RuntimeException exception) {
            // 当前 Q0 和公式 READY 已经成功，历史插值失败单独计量，后续由
            // Task 10 的迟到 Q0 派生扫描再次触发短缺口修复。
            meterRegistry.counter(
                    "iot.hvac.late_real.interpolation_failed").increment();
            log.error("迟到 Q0 已完成公式修正，但历史质量1回溯失败: pointId={}, minute={}",
                    event.pointId(), event.minuteStart(), exception);
        }
        meterRegistry.counter(
                "iot.hvac.late_real.corrected").increment();
    }

    private void publishReady(
            PointRuntimeConfig point,
            HvacLateRealEventStoredEvent event,
            long finalizedAt) {
        List<RawMinuteAggregate> completeMinute =
                minuteRepository.findByMinute(
                        event.minuteStart(), Set.of(point.buildingId()));
        boolean correctedRowVisible = completeMinute.stream().anyMatch(row ->
                row.pointId().equals(point.pointId())
                        && row.minuteStart() == event.minuteStart()
                        && row.dataQuality() == 0);
        if (!correctedRowVisible) {
            throw new IllegalStateException(
                    "迟到 Q0 写入成功但完整分钟回读未发现目标行");
        }

        eventPublisher.publishEvent(new HvacMinuteQualityReadyEvent(
                event.minuteStart(),
                finalizedAt,
                QualityEventSource.LATE_REAL_CORRECTION,
                Set.of(point.buildingId()),
                completeMinute,
                Set.of(point.pointId())));
    }

    /**
     * Q0 只有在完整统计证据一致时才能幂等退出。只判断质量或接收时间水位会在
     * 异步通知乱序时漏掉后落库的另一条真实样本。
     */
    private boolean sameRealEvidence(
            RawMinuteAggregate current,
            RawMinuteAggregate rebuilt) {
        return current.dataQuality() == 0
                && current.sampleCount() == rebuilt.sampleCount()
                && sameStoredValue(
                current.averageValue(), rebuilt.averageValue())
                && sameStoredValue(
                current.minimumValue(), rebuilt.minimumValue())
                && sameStoredValue(
                current.maximumValue(), rebuilt.maximumValue())
                && Objects.equals(
                current.firstReceivedTime(), rebuilt.firstReceivedTime())
                && Objects.equals(
                current.lastReceivedTime(), rebuilt.lastReceivedTime())
                && current.qualityTaskId() == null;
    }

    /**
     * 异步通知可能乱序执行；正式分钟的修正时间必须单调递增，Task 10 才能用它
     * 与指标 calculatedAt 比较并识别下游是否过期。
     */
    private long nextFinalizedAt(
            Optional<RawMinuteAggregate> current,
            long receivedAt) {
        long correctedAt = Math.max(System.currentTimeMillis(), receivedAt);
        if (current.isEmpty()) {
            return correctedAt;
        }
        return Math.max(
                correctedAt,
                Math.addExact(current.orElseThrow().finalizedAt(), 1L));
    }

    private boolean sameStoredValue(double current, double rebuilt) {
        return Double.isFinite(current)
                && Double.isFinite(rebuilt)
                && Math.abs(current - rebuilt) <= STORED_VALUE_TOLERANCE;
    }

    private MinuteQualityWriteResult requireSingleResult(
            RawMinuteAggregate aggregate,
            List<MinuteQualityWriteResult> results) {
        if (results == null || results.size() != 1) {
            throw new IllegalStateException("迟到 Q0 写入结果数量与请求不一致");
        }
        MinuteQualityWriteResult result =
                Objects.requireNonNull(results.getFirst(), "writeResult");
        if (!aggregate.pointId().equals(result.pointId())
                || aggregate.minuteStart() != result.minuteStart()) {
            throw new IllegalStateException("迟到 Q0 写入结果与请求不一致");
        }
        return result;
    }

    private boolean isActualWrite(
            MinuteQualityWriteResult.Outcome outcome) {
        return outcome == MinuteQualityWriteResult.Outcome.INSERTED
                || outcome == MinuteQualityWriteResult.Outcome.UPGRADED
                || outcome == MinuteQualityWriteResult.Outcome.UPDATED_REAL;
    }

    private void incrementReplacementBestEffort(String previousTaskId) {
        try {
            fillTaskRepository.incrementReplacedCount(previousTaskId, 1);
        } catch (RuntimeException exception) {
            // Q0 已成为 TDengine 正式事实；MySQL 计数失败由小时收口从
            // quality_task_id 重建，不能因此压掉公式 READY。
            log.error("迟到 Q0 已写入，但旧补全任务替换计数更新失败: taskId={}",
                    previousTaskId, exception);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
