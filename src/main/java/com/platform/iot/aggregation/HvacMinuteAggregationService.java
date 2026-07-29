package com.platform.iot.aggregation;

import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按设备采集时间批量生成正式分钟汇总。
 *
 * <p>正常运行时，一个到期分钟只查询一次 TDengine 原始事件超级表。
 * 查询结果只在本次计算期间临时驻留 Java 内存，不维护容易在重启时丢失的长期分钟桶。</p>
 */
@Slf4j
@Service
public class HvacMinuteAggregationService {

    private static final long MINUTE_MILLIS = 60_000L;

    private final DataPointConfigProvider configProvider;
    private final HvacRawEventRepository rawRepository;
    private final HvacMinuteRepository minuteRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final long finalizationDelayMillis;
    private final int catchUpMinutes;

    /**
     * 正常任务的进程内处理水位。
     *
     * <p>它只用于避免每 10 秒重复处理同一分钟，不作为持久化事实。
     * 应用重启后的缺口由 TDengine 恢复任务重新确认。</p>
     */
    private final AtomicLong lastProcessedMinute = new AtomicLong(Long.MIN_VALUE);

    public HvacMinuteAggregationService(
            DataPointConfigProvider configProvider,
            HvacRawEventRepository rawRepository,
            HvacMinuteRepository minuteRepository,
            ApplicationEventPublisher eventPublisher,
            @Value("${aggregation.finalization-delay-seconds:30}") int finalizationDelaySeconds,
            @Value("${aggregation.catch-up-minutes:10}") int catchUpMinutes) {
        this.configProvider = configProvider;
        this.rawRepository = rawRepository;
        this.minuteRepository = minuteRepository;
        this.eventPublisher = eventPublisher;
        this.finalizationDelayMillis = finalizationDelaySeconds * 1_000L;
        this.catchUpMinutes = Math.max(1, catchUpMinutes);
    }

    /**
     * 高频调度只负责发现新的到期分钟，不再扫描最近 10 分钟。
     */
    @Scheduled(
            fixedDelayString = "${aggregation.scan-delay-ms:10000}",
            initialDelayString = "${aggregation.scan-initial-delay-ms:10000}")
    public void finalizeDueMinutes() {
        finalizeDueMinutes(System.currentTimeMillis());
    }

    /**
     * 处理自上次成功水位以后新到期的分钟。
     *
     * <p>方法加锁是为了避免正常任务与恢复任务同时批量写同一个分钟。
     * 查询或保存失败时不推进水位，下一次调度会从失败分钟继续重试。</p>
     */
    public synchronized void finalizeDueMinutes(long now) {
        long latestDueMinute = latestDueMinute(now);
        long previous = lastProcessedMinute.get();
        long firstMinute = previous == Long.MIN_VALUE
                ? latestDueMinute : previous + MINUTE_MILLIS;
        if (firstMinute > latestDueMinute) {
            return;
        }

        for (long minute = firstMinute; minute <= latestDueMinute; minute += MINUTE_MILLIS) {
            try {
                processMinute(minute, now, false, null);
                // 无数据分钟也要推进水位；冻结边界之后的新事件会被标记为迟到，
                // 因此不能再进入这个正式分钟。
                lastProcessedMinute.set(minute);
            } catch (RuntimeException exception) {
                log.error("HVAC分钟批量聚合失败，保留水位等待下次重试: minute={}, error={}",
                        minute, exception.getMessage(), exception);
                break;
            }
        }
    }

    /**
     * 从 TDengine 恢复最近若干个到期分钟，只补齐缺失测点。
     *
     * <p>该方法只供应用启动和低频补漏调用，不参与每 10 秒的正常扫描。</p>
     */
    public synchronized void recoverRecentMinutes(long now) {
        Map<String, PointRuntimeConfig> activePoints = activePoints();
        if (activePoints.isEmpty()) {
            return;
        }

        long latestDueMinute = latestDueMinute(now);
        long earliest = latestDueMinute - (catchUpMinutes - 1L) * MINUTE_MILLIS;
        boolean completed = true;
        for (long minute = earliest; minute <= latestDueMinute; minute += MINUTE_MILLIS) {
            try {
                Set<String> existing = minuteRepository.findExistingPointIds(minute);
                Set<String> missing = new LinkedHashSet<>(activePoints.keySet());
                missing.removeAll(existing);
                if (missing.isEmpty()) {
                    continue;
                }
                processMinute(minute, now, true, missing);
            } catch (RuntimeException exception) {
                completed = false;
                log.error("HVAC分钟恢复失败，本轮停止并等待下次低频补漏: minute={}, error={}",
                        minute, exception.getMessage(), exception);
                break;
            }
        }

        if (completed) {
            // 启动恢复已经确认到最新到期分钟，正常任务无需马上重复处理它。
            lastProcessedMinute.accumulateAndGet(latestDueMinute, Math::max);
        }
    }

    private void processMinute(
            long minuteStart,
            long finalizedAt,
            boolean recovery,
            Set<String> onlyPointIds) {
        Map<String, PointRuntimeConfig> activePoints = activePoints();
        Set<String> buildingIds = activePoints.values().stream()
                .filter(point -> onlyPointIds == null
                        || onlyPointIds.contains(point.pointId()))
                .filter(point -> point.isForCalc() == 1)
                .filter(point -> "ANALOG".equalsIgnoreCase(point.dataType()))
                .map(PointRuntimeConfig::buildingId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<RawTelemetryEvent> rawEvents = rawRepository.findWindow(
                minuteStart, minuteStart + MINUTE_MILLIS, false);
        List<RawMinuteAggregate> aggregates =
                aggregate(rawEvents, minuteStart, finalizedAt, onlyPointIds, activePoints);
        if (aggregates.isEmpty()) {
            // 即使整分钟没有真实行，也要把活动计算建筑交给质量层即时尝试 Q2。
            if (!buildingIds.isEmpty()) {
                publishFrozenEvent(
                        minuteStart, finalizedAt, recovery, buildingIds, List.of());
            }
            return;
        }

        // 先持久化再通知公式模块，保证事件中的每个输入都能在st_raw_minute复审。
        List<MinuteQualityWriteResult> writeResults =
                minuteRepository.saveAllWithQualityPriority(aggregates, null);
        Set<MinuteKey> acceptedKeys = writeResults.stream()
                .filter(result -> switch (result.outcome()) {
                    case INSERTED, UPGRADED, UPDATED_REAL, IDEMPOTENT -> true;
                    case REJECTED_HIGHER_QUALITY, REJECTED_SAME_QUALITY -> false;
                })
                .map(result -> new MinuteKey(result.pointId(), result.minuteStart()))
                .collect(java.util.stream.Collectors.toSet());
        List<RawMinuteAggregate> accepted = aggregates.stream()
                .filter(row -> acceptedKeys.contains(
                        new MinuteKey(row.pointId(), row.minuteStart())))
                .toList();
        if (accepted.isEmpty()) {
            return;
        }
        publishFrozenEvent(
                minuteStart, finalizedAt, recovery, buildingIds, accepted);
        log.info("HVAC分钟批量数据已冻结: minute={}, points={}, recovery={}",
                minuteStart, accepted.size(), recovery);
    }

    private List<RawMinuteAggregate> aggregate(
            List<RawTelemetryEvent> events,
            long minuteStart,
            long finalizedAt,
            Set<String> onlyPointIds,
            Map<String, PointRuntimeConfig> activePoints) {
        Map<String, List<RawTelemetryEvent>> grouped = new LinkedHashMap<>();
        for (RawTelemetryEvent event : events) {
            if (!activePoints.containsKey(event.pointId())) {
                // 原始证据继续保留，但未配置或已停用测点不能进入正式分钟数据。
                continue;
            }
            if (onlyPointIds != null && !onlyPointIds.contains(event.pointId())) {
                continue;
            }
            grouped.computeIfAbsent(event.pointId(), ignored -> new ArrayList<>()).add(event);
        }

        List<RawMinuteAggregate> aggregates = new ArrayList<>();
        // 按测点配置顺序输出，便于测试、日志和后续公式输入保持稳定顺序。
        for (Map.Entry<String, PointRuntimeConfig> pointEntry : activePoints.entrySet()) {
            List<RawTelemetryEvent> pointEvents = grouped.get(pointEntry.getKey());
            if (pointEvents == null || pointEvents.isEmpty()) {
                continue;
            }
            aggregates.add(toAggregate(
                    pointEntry.getValue(), pointEvents, minuteStart, finalizedAt));
        }
        return aggregates;
    }

    private RawMinuteAggregate toAggregate(
            PointRuntimeConfig point,
            List<RawTelemetryEvent> events,
            long minuteStart,
            long finalizedAt) {
        DoubleSummaryStatistics statistics = events.stream()
                .mapToDouble(RawTelemetryEvent::value).summaryStatistics();
        int worstQuality = events.stream()
                .mapToInt(RawTelemetryEvent::dataQuality).max().orElse(0);
        long firstReceived = events.stream().map(RawTelemetryEvent::receivedTime)
                .min(Comparator.naturalOrder()).orElseThrow();
        long lastReceived = events.stream().map(RawTelemetryEvent::receivedTime)
                .max(Comparator.naturalOrder()).orElseThrow();
        return new RawMinuteAggregate(
                point.pointId(),
                point.pointCode(),
                point.buildingId(),
                point.systemGroupId(),
                point.equipId(),
                point.equipCode(),
                point.familyCode(),
                point.componentCode(),
                point.suffixCode(),
                point.isForCalc(),
                minuteStart,
                statistics.getAverage(),
                statistics.getMin(),
                statistics.getMax(),
                Math.toIntExact(statistics.getCount()),
                worstQuality,
                firstReceived,
                lastReceived,
                finalizedAt,
                null
        );
    }

    private Map<String, PointRuntimeConfig> activePoints() {
        Map<String, PointRuntimeConfig> result = new LinkedHashMap<>();
        for (PointRuntimeConfig point : configProvider.findAll()) {
            if ("ONLINE".equalsIgnoreCase(point.status())) {
                result.put(point.pointId(), point);
            }
        }
        return result;
    }

    private void publishFrozenEvent(
            long minuteStart,
            long finalizedAt,
            boolean recovery,
            Set<String> buildingIds,
            List<RawMinuteAggregate> aggregates) {
        try {
            eventPublisher.publishEvent(new HvacMinuteBatchFrozenEvent(
                    minuteStart, finalizedAt, recovery, buildingIds, aggregates));
        } catch (RuntimeException exception) {
            // 分钟批次已经成功落盘，不能因未来某个公式监听器异常而反复覆盖分钟数据。
            // 公式结果可通过低频补偿或人工重算从st_raw_minute恢复。
            log.error("HVAC分钟冻结事件发布失败，分钟数据已持久化: minute={}, error={}",
                    minuteStart, exception.getMessage(), exception);
        }
    }

    private long latestDueMinute(long now) {
        // 先减去等待时间再取自然分钟；30秒只决定何时冻结，不会扩大统计窗口。
        long effectiveNow = now - finalizationDelayMillis;
        long currentEffectiveMinute =
                effectiveNow - Math.floorMod(effectiveNow, MINUTE_MILLIS);
        return currentEffectiveMinute - MINUTE_MILLIS;
    }

    private record MinuteKey(String pointId, long minuteStart) {
    }
}
