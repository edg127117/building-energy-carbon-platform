package com.platform.hvac.service;

import com.platform.cache.IndicatorLatestCacheService;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.dto.HvacIndicatorDtos;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.formula.HvacFormulaEngine;
import com.platform.iot.formula.IndicatorConfigProvider;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorLatestState;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.system.service.BuildingScopeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * HVAC 公式指标的只读查询编排。
 *
 * <p>MySQL 指标配置决定可见范围和稳定输出顺序，Redis 只承担最新状态加速，
 * TDengine 是成功趋势和异常审计的长期真相来源。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "formula", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class HvacIndicatorQueryService {

    private static final long MAX_HISTORY_SPAN = Duration.ofDays(31).toMillis();
    private static final Comparator<BizIndicator> INDICATOR_ORDER = Comparator
            .comparing(BizIndicator::getIndicatorCode,
                    Comparator.nullsLast(String::compareTo))
            .thenComparing(BizIndicator::getIndicatorId,
                    Comparator.nullsLast(String::compareTo));

    private final BuildingService buildingService;
    private final BuildingScopeService buildingScopeService;
    private final IndicatorConfigProvider configProvider;
    private final IndicatorLatestCacheService cache;
    private final IndicatorMinuteRepository indicatorRepository;
    private final HvacMinuteRepository minuteRepository;
    private final HvacFormulaEngine formulaEngine;

    public HvacIndicatorQueryService(
            BuildingService buildingService,
            BuildingScopeService buildingScopeService,
            IndicatorConfigProvider configProvider,
            IndicatorLatestCacheService cache,
            IndicatorMinuteRepository indicatorRepository,
            HvacMinuteRepository minuteRepository,
            HvacFormulaEngine formulaEngine) {
        this.buildingService = buildingService;
        this.buildingScopeService = buildingScopeService;
        this.configProvider = configProvider;
        this.cache = cache;
        this.indicatorRepository = indicatorRepository;
        this.minuteRepository = minuteRepository;
        this.formulaEngine = formulaEngine;
    }

    /** 返回建筑全部活动指标的最新一次计算状态。 */
    public HvacIndicatorDtos.LatestResponse latest(
            String buildingId, Long userId, Set<String> roles) {
        checkBuildingAccess(buildingId, userId, roles);
        List<BizIndicator> indicators = configProvider.findAllActive().stream()
                .filter(indicator -> Integer.valueOf(1).equals(indicator.getStatus()))
                .filter(indicator -> buildingId.equals(indicator.getBuildingId()))
                .sorted(INDICATOR_ORDER)
                .toList();

        Map<String, HvacIndicatorDtos.LatestIndicator> resolved =
                new LinkedHashMap<>();
        List<BizIndicator> misses = new ArrayList<>();
        for (BizIndicator indicator : indicators) {
            Optional<IndicatorLatestState> cached = cache.get(
                    indicator.getIndicatorId());
            if (cached.isPresent() && cacheMatches(indicator, cached.orElseThrow())) {
                resolved.put(indicator.getIndicatorId(),
                        fromCache(indicator, cached.orElseThrow()));
            } else {
                misses.add(indicator);
            }
        }

        if (!misses.isEmpty()) {
            List<String> missingIds = misses.stream()
                    .map(BizIndicator::getIndicatorId)
                    .toList();
            LatestRows rows = queryTdengine(() -> new LatestRows(
                    indicatorRepository.findLatestSuccesses(missingIds),
                    indicatorRepository.findLatestExceptions(missingIds)));
            Map<String, IndicatorMinuteResult> successes =
                    latestSuccessesById(rows.successes(), misses);
            Map<String, FormulaCalculationException> exceptions =
                    latestExceptionsById(rows.exceptions(), misses);
            for (BizIndicator indicator : misses) {
                resolved.put(indicator.getIndicatorId(), mergeLatest(
                        indicator,
                        successes.get(indicator.getIndicatorId()),
                        exceptions.get(indicator.getIndicatorId())));
            }
        }

        List<HvacIndicatorDtos.LatestIndicator> response = indicators.stream()
                .map(indicator -> resolved.get(indicator.getIndicatorId()))
                .toList();
        return new HvacIndicatorDtos.LatestResponse(
                buildingId, System.currentTimeMillis(), response);
    }

    /** 查询单一指标在半开区间 {@code [from,to)} 内的成功趋势。 */
    public HvacIndicatorDtos.HistoryResponse history(
            String indicatorId,
            Long from,
            Long to,
            Long userId,
            Set<String> roles) {
        BizIndicator indicator = requireAccessibleIndicator(
                indicatorId, userId, roles);
        validateHistoryRange(from, to);
        List<HvacIndicatorDtos.HistoryRecord> records = queryTdengine(
                () -> indicatorRepository.findHistory(
                        indicatorId, from, to)).stream()
                .filter(row -> successMatches(indicator, row))
                .filter(row -> row.minuteStart() >= from && row.minuteStart() < to)
                .sorted(Comparator.comparingLong(
                        IndicatorMinuteResult::minuteStart))
                .map(row -> new HvacIndicatorDtos.HistoryRecord(
                        row.minuteStart(),
                        row.value(),
                        row.dataQuality(),
                        row.formulaVersion()))
                .toList();
        return new HvacIndicatorDtos.HistoryResponse(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                from,
                to,
                records);
    }

    /** 返回指定来源分钟的成功公式过程或失败审计。 */
    public HvacIndicatorDtos.CalculationDetail detail(
            String indicatorId,
            long minuteStart,
            Long userId,
            Set<String> roles) {
        BizIndicator indicator = requireAccessibleIndicator(
                indicatorId, userId, roles);
        Optional<IndicatorLatestState> cached = cache.get(indicatorId);
        if (cached.isPresent()
                && cached.orElseThrow().minuteStart() == minuteStart
                && cacheMatches(indicator, cached.orElseThrow())) {
            return fromCacheDetail(indicator, cached.orElseThrow());
        }

        PersistedAttempt attempt = queryTdengine(() -> new PersistedAttempt(
                indicatorRepository.findSuccess(indicatorId, minuteStart)
                        .filter(row -> successMatches(indicator, row)),
                indicatorRepository.findException(indicatorId, minuteStart)
                        .filter(row -> exceptionMatches(indicator, row))));
        // 同一分钟恢复成功后，历史异常仍可能保留；成功结果具有最终优先级。
        if (attempt.success().isPresent()) {
            IndicatorMinuteResult success = attempt.success().orElseThrow();
            List<RawMinuteAggregate> aggregates = queryTdengine(
                    () -> minuteRepository.findByMinute(
                            minuteStart, Set.of(indicator.getBuildingId())));
            FormulaCalculation calculation = formulaEngine.explain(
                    indicator,
                    minuteStart,
                    aggregates,
                    success.formulaVersion());
            return fromCalculation(indicator, minuteStart, calculation);
        }
        if (attempt.exception().isPresent()) {
            return fromException(indicator, attempt.exception().orElseThrow());
        }
        return noDataDetail(indicator, minuteStart);
    }

    private BizIndicator requireAccessibleIndicator(
            String indicatorId, Long userId, Set<String> roles) {
        BizIndicator indicator = configProvider.findActive(indicatorId)
                .filter(value -> Integer.valueOf(1).equals(value.getStatus()))
                .orElseThrow(() -> new BusinessException(
                        404, "指标不存在或已停用"));
        checkBuildingAccess(indicator.getBuildingId(), userId, roles);
        return indicator;
    }

    private void checkBuildingAccess(
            String buildingId, Long userId, Set<String> roles) {
        if (buildingService.getById(buildingId) == null) {
            throw new BusinessException(404, "建筑不存在");
        }
        buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    private void validateHistoryRange(Long from, Long to) {
        if (from == null || to == null) {
            throw new BusinessException(400, "from 和 to 为必填毫秒时间戳");
        }
        if (from >= to) {
            throw new BusinessException(400, "from 必须小于 to");
        }
        long span;
        try {
            span = Math.subtractExact(to, from);
        } catch (ArithmeticException exception) {
            throw new BusinessException(400, "历史查询时间范围无效");
        }
        if (span > MAX_HISTORY_SPAN) {
            throw new BusinessException(400, "历史查询跨度不能超过 31 天");
        }
    }

    private boolean cacheMatches(
            BizIndicator indicator, IndicatorLatestState state) {
        return Objects.equals(indicator.getIndicatorId(), state.indicatorId())
                && Objects.equals(indicator.getIndicatorCode(), state.indicatorCode())
                && Objects.equals(indicator.getBuildingId(), state.buildingId())
                && Objects.equals(indicator.getEquipId(), state.equipId())
                && state.status() != null;
    }

    private boolean successMatches(
            BizIndicator indicator, IndicatorMinuteResult row) {
        return Objects.equals(indicator.getIndicatorId(), row.indicatorId())
                && Objects.equals(indicator.getIndicatorCode(), row.indicatorCode())
                && Objects.equals(indicator.getBuildingId(), row.buildingId())
                && Objects.equals(indicator.getEquipId(), row.equipId());
    }

    private boolean exceptionMatches(
            BizIndicator indicator, FormulaCalculationException row) {
        return Objects.equals(indicator.getIndicatorId(), row.indicatorId())
                && Objects.equals(indicator.getIndicatorCode(), row.indicatorCode())
                && Objects.equals(indicator.getBuildingId(), row.buildingId())
                && Objects.equals(indicator.getEquipId(), row.equipId());
    }

    private Map<String, IndicatorMinuteResult> latestSuccessesById(
            List<IndicatorMinuteResult> rows, List<BizIndicator> indicators) {
        Map<String, BizIndicator> metadata = indicators.stream().collect(
                java.util.stream.Collectors.toMap(
                        BizIndicator::getIndicatorId, indicator -> indicator));
        Map<String, IndicatorMinuteResult> result = new LinkedHashMap<>();
        for (IndicatorMinuteResult row : rows) {
            BizIndicator indicator = metadata.get(row.indicatorId());
            if (indicator != null && successMatches(indicator, row)) {
                result.merge(row.indicatorId(), row, this::newerSuccess);
            }
        }
        return result;
    }

    private Map<String, FormulaCalculationException> latestExceptionsById(
            List<FormulaCalculationException> rows, List<BizIndicator> indicators) {
        Map<String, BizIndicator> metadata = indicators.stream().collect(
                java.util.stream.Collectors.toMap(
                        BizIndicator::getIndicatorId, indicator -> indicator));
        Map<String, FormulaCalculationException> result = new LinkedHashMap<>();
        for (FormulaCalculationException row : rows) {
            BizIndicator indicator = metadata.get(row.indicatorId());
            if (indicator != null && exceptionMatches(indicator, row)) {
                result.merge(row.indicatorId(), row, this::newerException);
            }
        }
        return result;
    }

    private IndicatorMinuteResult newerSuccess(
            IndicatorMinuteResult left, IndicatorMinuteResult right) {
        if (left.minuteStart() != right.minuteStart()) {
            return left.minuteStart() > right.minuteStart() ? left : right;
        }
        return left.calculatedAt() >= right.calculatedAt() ? left : right;
    }

    private FormulaCalculationException newerException(
            FormulaCalculationException left,
            FormulaCalculationException right) {
        if (left.minuteStart() != right.minuteStart()) {
            return left.minuteStart() > right.minuteStart() ? left : right;
        }
        return left.calculatedAt() >= right.calculatedAt() ? left : right;
    }

    private HvacIndicatorDtos.LatestIndicator mergeLatest(
            BizIndicator indicator,
            IndicatorMinuteResult success,
            FormulaCalculationException exception) {
        if (success != null && exception != null
                && success.minuteStart() == exception.minuteStart()) {
            return fromSuccess(indicator, success);
        }
        if (exception != null
                && (success == null
                || exception.minuteStart() > success.minuteStart())) {
            return latestFromException(indicator, exception);
        }
        return success == null
                ? noData(indicator)
                : fromSuccess(indicator, success);
    }

    private HvacIndicatorDtos.LatestIndicator fromCache(
            BizIndicator indicator, IndicatorLatestState state) {
        return new HvacIndicatorDtos.LatestIndicator(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getEquipId(),
                state.minuteStart(),
                state.status().name(),
                state.status() == FormulaCalculation.Status.SUCCESS
                        ? state.value() : null,
                unit(indicator.getIndicatorCode()),
                state.status() == FormulaCalculation.Status.SUCCESS
                        ? state.dataQuality() : null,
                state.formulaVersion(),
                state.reasonCode(),
                state.missingInputs());
    }

    private HvacIndicatorDtos.LatestIndicator fromSuccess(
            BizIndicator indicator, IndicatorMinuteResult row) {
        return new HvacIndicatorDtos.LatestIndicator(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getEquipId(),
                row.minuteStart(),
                FormulaCalculation.Status.SUCCESS.name(),
                row.value(),
                unit(indicator.getIndicatorCode()),
                row.dataQuality(),
                row.formulaVersion(),
                null,
                List.of());
    }

    private HvacIndicatorDtos.LatestIndicator latestFromException(
            BizIndicator indicator, FormulaCalculationException row) {
        return new HvacIndicatorDtos.LatestIndicator(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getEquipId(),
                row.minuteStart(),
                row.status().name(),
                null,
                unit(indicator.getIndicatorCode()),
                null,
                row.formulaVersion(),
                row.reasonCode(),
                row.missingInputs());
    }

    private HvacIndicatorDtos.LatestIndicator noData(BizIndicator indicator) {
        return new HvacIndicatorDtos.LatestIndicator(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getEquipId(),
                null,
                "NO_DATA",
                null,
                unit(indicator.getIndicatorCode()),
                null,
                null,
                null,
                List.of());
    }

    private HvacIndicatorDtos.CalculationDetail fromCacheDetail(
            BizIndicator indicator, IndicatorLatestState state) {
        return new HvacIndicatorDtos.CalculationDetail(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getEquipId(),
                state.minuteStart(),
                state.status().name(),
                state.status() == FormulaCalculation.Status.SUCCESS
                        ? state.value() : null,
                unit(indicator.getIndicatorCode()),
                state.status() == FormulaCalculation.Status.SUCCESS
                        ? state.dataQuality() : null,
                state.formulaVersion(),
                state.inputs(),
                state.steps(),
                state.reasonCode(),
                state.missingInputs());
    }

    private HvacIndicatorDtos.CalculationDetail fromCalculation(
            BizIndicator indicator,
            long minuteStart,
            FormulaCalculation calculation) {
        boolean success = calculation.status()
                == FormulaCalculation.Status.SUCCESS;
        return new HvacIndicatorDtos.CalculationDetail(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getEquipId(),
                minuteStart,
                calculation.status().name(),
                success ? calculation.value() : null,
                unit(indicator.getIndicatorCode()),
                success ? calculation.dataQuality() : null,
                calculation.formulaVersion(),
                calculation.inputs(),
                calculation.steps(),
                calculation.reasonCode(),
                calculation.missingInputs());
    }

    private HvacIndicatorDtos.CalculationDetail fromException(
            BizIndicator indicator, FormulaCalculationException row) {
        return new HvacIndicatorDtos.CalculationDetail(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getEquipId(),
                row.minuteStart(),
                row.status().name(),
                null,
                unit(indicator.getIndicatorCode()),
                null,
                row.formulaVersion(),
                List.of(),
                List.of(),
                row.reasonCode(),
                row.missingInputs());
    }

    private HvacIndicatorDtos.CalculationDetail noDataDetail(
            BizIndicator indicator, long minuteStart) {
        return new HvacIndicatorDtos.CalculationDetail(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getEquipId(),
                minuteStart,
                "NO_DATA",
                null,
                unit(indicator.getIndicatorCode()),
                null,
                null,
                List.of(),
                List.of(),
                null,
                List.of());
    }

    private String unit(String indicatorCode) {
        return switch (indicatorCode) {
            case "WCR_COP" -> null;
            case "TOWER_EFF", "PUMP_EFF" -> "%";
            case "AHU_POW_EFF" -> "W/(m³/h)";
            default -> null;
        };
    }

    private <T> T queryTdengine(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    503, "HVAC 指标时序数据暂不可用，请稍后重试");
        }
    }

    private record LatestRows(
            List<IndicatorMinuteResult> successes,
            List<FormulaCalculationException> exceptions) {
    }

    private record PersistedAttempt(
            Optional<IndicatorMinuteResult> success,
            Optional<FormulaCalculationException> exception) {
    }
}
