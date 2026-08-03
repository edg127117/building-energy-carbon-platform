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
     * 接收分钟冻结事件，先确定本分钟的正式输入，再触发公式和历史短缺口修正。
     *
     * <p>恢复事件会先从 TDengine 回读建筑完整分钟，普通冻结事件直接使用聚合结果；
     * 对仍缺失的活动计算点才尝试写入 Q2。READY 采用同步事件发布，因此只有本分钟
     * 公式处理返回后，才把其中的 Q0 交给 {@link InterpolationFillService} 回溯 Q1，
     * 避免历史修正拖慢或抢先于当前分钟结果。</p>
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

    /** 只选择事件建筑内在线、模拟量且参与计算的测点，作为本分钟完整性集合。 */
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
