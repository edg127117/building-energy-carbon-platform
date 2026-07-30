package com.platform.iot.dataquality;

import com.platform.iot.aggregation.HvacMinuteBatchFrozenEvent;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在真实分钟冻结和公式计算之间完成当前分钟的数据质量选择。
 *
 * <p>服务只为活动模拟计算点尝试即时质量 2；没有合法来源时仍发布 READY，
 * 让公式明确记录缺失。完整质量 0 分钟不会访问典型值或补全任务仓储。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "data-quality", name = "enabled", havingValue = "true")
public class HvacMinuteQualityCompletionService {

    private final DataPointConfigProvider pointConfigProvider;
    private final HvacMinuteRepository minuteRepository;
    private final TypicalValueFillService typicalValueFillService;
    private final InterpolationFillService interpolationFillService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public HvacMinuteQualityCompletionService(
            DataPointConfigProvider pointConfigProvider,
            HvacMinuteRepository minuteRepository,
            TypicalValueFillService typicalValueFillService,
            InterpolationFillService interpolationFillService,
            ApplicationEventPublisher eventPublisher) {
        this.pointConfigProvider =
                Objects.requireNonNull(pointConfigProvider, "pointConfigProvider");
        this.minuteRepository =
                Objects.requireNonNull(minuteRepository, "minuteRepository");
        this.typicalValueFillService =
                Objects.requireNonNull(typicalValueFillService, "typicalValueFillService");
        this.interpolationFillService =
                Objects.requireNonNull(interpolationFillService, "interpolationFillService");
        this.eventPublisher =
                Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    /**
     * 同步处理冻结事件，READY 发布返回后 Task 7 才会追加历史 Q1 回溯，
     * 从而确保当前分钟公式结果优先产生。
     */
    @EventListener
    public void onMinuteFrozen(HvacMinuteBatchFrozenEvent event) {
        Map<String, PointRuntimeConfig> targets =
                targetPoints(event.buildingIds(), pointConfigProvider.findAll());
        Map<String, RawMinuteAggregate> completed = new LinkedHashMap<>();
        List<RawMinuteAggregate> persistedInputs = event.recovery()
                ? minuteRepository.findByMinute(
                        event.minuteStart(), event.buildingIds())
                : event.aggregates();
        persistedInputs.stream()
                .forEach(row -> completed.put(row.pointId(), row));

        boolean typicalFilled = false;
        for (PointRuntimeConfig target : targets.values()) {
            if (completed.containsKey(target.pointId())) {
                continue;
            }
            var generated = typicalValueFillService.fillMissing(
                    target, event.minuteStart(), event.finalizedAt());
            if (generated.isPresent()) {
                RawMinuteAggregate resolved = generated.orElseThrow();
                completed.put(target.pointId(), resolved);
                // 并发更高质量写入被回读时，READY 来源不能误标为典型值补全。
                typicalFilled |= resolved.dataQuality() == 2;
            }
        }

        List<RawMinuteAggregate> readyInputs;
        Set<String> affectedPointIds;
        if (event.recovery()) {
            affectedPointIds = event.aggregates().stream()
                    .map(RawMinuteAggregate::pointId)
                    .collect(java.util.stream.Collectors.toCollection(
                            LinkedHashSet::new));
        } else {
            affectedPointIds = Set.of();
        }
        // 恢复事件先回读完整分钟再判断缺失，避免把库中已有 Q0/Q1 误当缺点并创建无用 Q2 任务。
        readyInputs = completed.values().stream()
                .sorted(Comparator.comparing(RawMinuteAggregate::buildingId)
                        .thenComparing(RawMinuteAggregate::pointId))
                .toList();

        eventPublisher.publishEvent(new HvacMinuteQualityReadyEvent(
                event.minuteStart(),
                event.finalizedAt(),
                typicalFilled
                        ? QualityEventSource.TYPICAL_FILL
                        : QualityEventSource.NORMAL_FREEZE,
                event.buildingIds(),
                readyInputs,
                affectedPointIds));

        // Spring 默认同步处理 READY；只有当前分钟公式已经完成后，才回溯历史短缺口。
        // 五分钟是历史升级范围，不是当前分钟等待窗口，因此此调用必须位于 READY 之后。
        interpolationFillService.fillFromRightEndpoints(
                readyInputs.stream()
                        .filter(row -> row.dataQuality() == 0)
                        .toList(),
                event.finalizedAt());
    }

    private Map<String, PointRuntimeConfig> targetPoints(
            Set<String> buildingIds,
            Collection<PointRuntimeConfig> points) {
        Map<String, PointRuntimeConfig> targets = new LinkedHashMap<>();
        for (PointRuntimeConfig point : points) {
            if (buildingIds.contains(point.buildingId())
                    && "ONLINE".equalsIgnoreCase(point.status())
                    && "ANALOG".equalsIgnoreCase(point.dataType())
                    && point.isForCalc() == 1) {
                targets.put(point.pointId(), point);
            }
        }
        return targets;
    }
}
