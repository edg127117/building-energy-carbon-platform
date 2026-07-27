package com.platform.iot.formula;

import com.platform.config.FormulaProperties;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 低频检查已冻结分钟的指标成功结果，只重算缺少的指标实例。
 *
 * <p>指标配置和窗口内的成功键都只批量读取一次；各分钟按时间顺序串行处理，
 * 单个分钟查询或计算失败不会阻断后续分钟。TDengine 中指标时间戳的幂等写入
 * 是最终重复执行保护。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "formula",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class HvacFormulaRecoveryService {

    private static final long MINUTE_MILLIS = 60_000L;

    private final IndicatorConfigProvider configProvider;
    private final IndicatorMinuteRepository indicatorRepository;
    private final HvacMinuteRepository minuteRepository;
    private final HvacFormulaEngine engine;
    private final FormulaProperties properties;
    private final long aggregationFinalizationDelayMillis;

    public HvacFormulaRecoveryService(
            IndicatorConfigProvider configProvider,
            IndicatorMinuteRepository indicatorRepository,
            HvacMinuteRepository minuteRepository,
            HvacFormulaEngine engine,
            FormulaProperties properties,
            @Value("${aggregation.finalization-delay-seconds:30}")
            int aggregationFinalizationDelaySeconds) {
        this.configProvider = configProvider;
        this.indicatorRepository = indicatorRepository;
        this.minuteRepository = minuteRepository;
        this.engine = engine;
        this.properties = properties;
        this.aggregationFinalizationDelayMillis = Math.multiplyExact(
                aggregationFinalizationDelaySeconds, 1_000L);
    }

    /**
     * 扫描最近配置窗口内已经到达冻结边界、但没有成功指标行的分钟。
     *
     * @param now 本轮统一使用的当前时间，同时作为重算结果的 calculatedAt
     */
    public synchronized void recover(long now) {
        Map<String, BizIndicator> activeById =
                activeIndicatorsById(configProvider.findAllActive());
        if (activeById.isEmpty()) {
            return;
        }

        int recoveryMinutes = Math.max(1, properties.getRecoveryMinutes());
        long latestDueMinute = latestDueMinute(now);
        long firstMinute = latestDueMinute
                - (recoveryMinutes - 1L) * MINUTE_MILLIS;
        long toExclusive = latestDueMinute + MINUTE_MILLIS;
        List<String> indicatorIds = List.copyOf(activeById.keySet());
        Set<IndicatorMinuteKey> successfulKeys =
                indicatorRepository.findSuccessfulKeys(
                        indicatorIds, firstMinute, toExclusive);

        for (long minute = firstMinute;
             minute <= latestDueMinute;
             minute += MINUTE_MILLIS) {
            Set<String> missingIndicatorIds = missingIndicatorIds(
                    indicatorIds, successfulKeys, minute);
            if (missingIndicatorIds.isEmpty()) {
                continue;
            }

            try {
                Set<String> buildingIds = buildingIds(
                        activeById, missingIndicatorIds);
                if (buildingIds.isEmpty()) {
                    continue;
                }
                List<RawMinuteAggregate> aggregates =
                        minuteRepository.findByMinute(minute, buildingIds);
                if (aggregates.isEmpty()) {
                    continue;
                }
                engine.calculateAndPersist(
                        minute, now, aggregates, missingIndicatorIds);
            } catch (RuntimeException exception) {
                log.error(
                        "HVAC指标缺口补算失败，继续处理后续分钟: minute={}, indicatorIds={}, error={}",
                        minute, missingIndicatorIds, exception.getMessage(),
                        exception);
            }
        }
    }

    private Map<String, BizIndicator> activeIndicatorsById(
            Collection<BizIndicator> indicators) {
        Map<String, BizIndicator> activeById = new LinkedHashMap<>();
        indicators.stream()
                .filter(indicator -> indicator != null
                        && indicator.getIndicatorId() != null
                        && !indicator.getIndicatorId().isBlank())
                .sorted(Comparator.comparing(BizIndicator::getIndicatorId))
                .forEach(indicator ->
                        activeById.putIfAbsent(
                                indicator.getIndicatorId(), indicator));
        return activeById;
    }

    private Set<String> missingIndicatorIds(
            List<String> indicatorIds,
            Set<IndicatorMinuteKey> successfulKeys,
            long minuteStart) {
        Set<String> missing = new LinkedHashSet<>();
        for (String indicatorId : indicatorIds) {
            if (!successfulKeys.contains(
                    new IndicatorMinuteKey(indicatorId, minuteStart))) {
                missing.add(indicatorId);
            }
        }
        return Set.copyOf(missing);
    }

    private Set<String> buildingIds(
            Map<String, BizIndicator> activeById,
            Set<String> missingIndicatorIds) {
        Set<String> buildingIds = new LinkedHashSet<>();
        missingIndicatorIds.stream()
                .map(activeById::get)
                .map(BizIndicator::getBuildingId)
                .filter(buildingId ->
                        buildingId != null && !buildingId.isBlank())
                .sorted()
                .forEach(buildingIds::add);
        return Set.copyOf(buildingIds);
    }

    private long latestDueMinute(long now) {
        long effectiveNow = now - aggregationFinalizationDelayMillis;
        long currentEffectiveMinute =
                effectiveNow - Math.floorMod(effectiveNow, MINUTE_MILLIS);
        return currentEffectiveMinute - MINUTE_MILLIS;
    }
}
