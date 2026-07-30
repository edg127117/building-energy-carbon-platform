package com.platform.iot.dataquality;

import com.platform.iot.aggregation.HvacMinuteBatchFrozenEvent;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据质量模块关闭时保留原有真实分钟到公式的通路。
 *
 * <p>空真实分钟不会转发，避免关闭补全后凭空触发公式；恢复批次会携带变化点位，
 * 使公式仍按完整分钟回读。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "data-quality", name = "enabled", havingValue = "false")
public class HvacMinuteQualityBypassListener {

    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onMinuteFrozen(HvacMinuteBatchFrozenEvent event) {
        if (event.aggregates().isEmpty()) {
            return;
        }
        Set<String> affectedPointIds = event.recovery()
                ? event.aggregates().stream()
                .map(RawMinuteAggregate::pointId)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                : Set.of();
        eventPublisher.publishEvent(new HvacMinuteQualityReadyEvent(
                event.minuteStart(),
                event.finalizedAt(),
                QualityEventSource.NORMAL_FREEZE,
                event.buildingIds(),
                event.aggregates(),
                affectedPointIds));
    }
}
