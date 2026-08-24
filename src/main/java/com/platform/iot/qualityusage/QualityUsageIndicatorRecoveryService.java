package com.platform.iot.qualityusage;

import com.platform.config.DataQualityProperties;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.formula.HvacFormulaEngine;
import com.platform.iot.formula.IndicatorConfigProvider;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.platform.iot.qualityusage.QualityUsageModels.INDICATOR_CALCULATION;

@Service
@ConditionalOnProperty(
        prefix = "formula", name = "enabled",
        havingValue = "true", matchIfMissing = true)
/** 策略修订切换后，对自动纠正窗口内受影响指标执行有界重算。 */
public class QualityUsageIndicatorRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(QualityUsageIndicatorRecoveryService.class);

    private final HvacMinuteRepository minuteRepository;
    private final IndicatorConfigProvider indicatorConfigProvider;
    private final HvacFormulaEngine formulaEngine;
    private final DataQualityProperties dataQualityProperties;
    private final QualityUsageProperties properties;
    private final QualityUsageRecoveryTaskService recoveryTasks;
    private final ThreadPoolTaskExecutor executor;

    public QualityUsageIndicatorRecoveryService(
            HvacMinuteRepository minuteRepository,
            IndicatorConfigProvider indicatorConfigProvider,
            HvacFormulaEngine formulaEngine,
            DataQualityProperties dataQualityProperties,
            QualityUsageProperties properties,
            QualityUsageRecoveryTaskService recoveryTasks,
            @Qualifier("qualityUsageIndicatorExecutor") ThreadPoolTaskExecutor executor) {
        this.minuteRepository = minuteRepository;
        this.indicatorConfigProvider = indicatorConfigProvider;
        this.formulaEngine = formulaEngine;
        this.dataQualityProperties = dataQualityProperties;
        this.properties = properties;
        this.recoveryTasks = recoveryTasks;
        this.executor = executor;
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        Gauge.builder("quality.usage.indicator.recovery.backlog", executor,
                        value -> value.getThreadPoolExecutor().getQueue().size())
                .register(registry);
    }

    @EventListener
    public void onRuntimeRefreshed(QualityUsageRuntimeRefreshedEvent event) {
        Set<String> pointIds = event.affectedPolicies().stream()
                .filter(key -> INDICATOR_CALCULATION.equals(key.scenarioCode()))
                .map(PolicyKey::pointId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (pointIds.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> recover(
                    event.currentRevision(), event.earliestAffectedMinute(), pointIds));
        } catch (TaskRejectedException exception) {
            recoveryTasks.recordQueueOverflow(event.currentRevision(), "INDICATOR");
        }
    }

    private void recover(long revision, Long earliestAffectedMinute, Set<String> pointIds) {
        long to = QualityUsagePolicyResolver.alignMinute(System.currentTimeMillis()) + 60_000L;
        long automaticFrom = to - Duration.ofHours(
                dataQualityProperties.getLateRealCorrectionHours()).toMillis();
        if (earliestAffectedMinute != null && earliestAffectedMinute < automaticFrom) {
            recoveryTasks.recordRecoveryWindowExceeded(
                    revision, earliestAffectedMinute, automaticFrom);
        }
        long from = earliestAffectedMinute == null
                ? automaticFrom : Math.max(automaticFrom, earliestAffectedMinute);
        List<RawMinuteAggregate> changedRows = minuteRepository.findRange(pointIds, from, to);
        List<Long> allMinutes = changedRows.stream()
                .map(RawMinuteAggregate::minuteStart)
                .distinct().sorted().toList();
        int limit = properties.getRecoveryBatchSize();
        if (allMinutes.size() > limit) {
            recoveryTasks.recordManualRecoveryRequired(revision, allMinutes.size() - limit);
        }
        List<BizIndicator> activeIndicators = indicatorConfigProvider.findAllActive().stream()
                .filter(indicator -> Integer.valueOf(1).equals(indicator.getStatus()))
                .toList();
        Set<String> indicatorIds = formulaEngine.resolveAffectedIndicatorIds(
                activeIndicators, pointIds);
        if (indicatorIds.isEmpty()) {
            return;
        }
        Set<String> buildings = activeIndicators.stream()
                .filter(indicator -> indicatorIds.contains(indicator.getIndicatorId()))
                .map(BizIndicator::getBuildingId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        allMinutes.stream().limit(limit).forEach(minute -> {
            try {
                formulaEngine.recalculateForQualityPolicy(
                        minute,
                        minuteRepository.findByMinute(minute, buildings),
                        indicatorIds);
            } catch (RuntimeException exception) {
                log.warn("Quality policy indicator recovery failed: revision={}, minute={}",
                        revision, minute, exception);
            }
        });
    }
}
