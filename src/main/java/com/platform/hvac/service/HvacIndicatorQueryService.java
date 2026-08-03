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
 * TDengine 是成功趋势和异常审计的长期真相来源。上游由指标 Controller 调用，
 * 结果返回 HVAC 页面最新卡片、历史趋势或计算详情。本类按“权限与活动配置 →
 * 缓存/时序回退 → 成功和异常合并 → DTO 组装”组织流程，不负责执行在线指标计算
 * 或持久化公式结果。</p>
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

    /**
     * 返回建筑全部活动指标的最新一次计算状态。
     *
     * <p>先逐指标读取 Redis，未命中或缓存身份不匹配的部分再批量查询 TDengine。
     * 缓存仅用于加速，不能替代 MySQL 的活动配置范围或 TDengine 的历史真相。</p>
     */
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

    /**
     * 查询单一指标在半开区间 {@code [from,to)} 内的成功趋势。
     *
     * <p>必须同时提供起止毫秒时间戳，跨度最多 31 天；缺参、逆序或超长返回
     * 400，避免无界扫描 TDengine。</p>
     */
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

    /**
     * 返回指定来源分钟的成功公式过程或失败审计。
     *
     * <p>最新分钟可以直接使用 Redis 中的输入和步骤；历史成功结果必须读取同
     * 一分钟源数据并按其持久化公式版本重放。同一分钟既有旧异常又有补算成功
     * 时，以成功结果为最终状态。</p>
     */
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

    /**
     * 从 MySQL 活动配置中取得指标，并按其建筑归属校验当前用户数据范围。
     *
     * <p>指标不存在或停用返回 404，存在但建筑未授权返回 403；历史和计算详情入口
     * 共用该边界，不能仅凭调用方提供的指标 ID 读取 TDengine。</p>
     */
    private BizIndicator requireAccessibleIndicator(
            String indicatorId, Long userId, Set<String> roles) {
        BizIndicator indicator = configProvider.findActive(indicatorId)
                .filter(value -> Integer.valueOf(1).equals(value.getStatus()))
                .orElseThrow(() -> new BusinessException(
                        404, "指标不存在或已停用"));
        checkBuildingAccess(indicator.getBuildingId(), userId, roles);
        return indicator;
    }

    /**
     * 在查询指标缓存或时序结果前区分建筑不存在与建筑未授权。
     * MySQL 建筑缺失返回 404，建筑范围服务拒绝时返回 403。
     */
    private void checkBuildingAccess(
            String buildingId, Long userId, Set<String> roles) {
        if (buildingService.getById(buildingId) == null) {
            throw new BusinessException(404, "建筑不存在");
        }
        buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    /**
     * 校验指标历史查询的半开区间 {@code [from,to)}。
     * 缺参、逆序、跨度运算溢出或超过 31 天均返回 400，避免无界扫描 TDengine。
     */
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

    /**
     * 确认 Redis 最新状态仍属于当前 MySQL 指标身份。
     *
     * <p>指标编码、建筑或设备变化以及缺少状态时视为缓存未命中，防止陈旧缓存把
     * 其他作用域结果返回给当前配置。</p>
     */
    private boolean cacheMatches(
            BizIndicator indicator, IndicatorLatestState state) {
        return Objects.equals(indicator.getIndicatorId(), state.indicatorId())
                && Objects.equals(indicator.getIndicatorCode(), state.indicatorCode())
                && Objects.equals(indicator.getBuildingId(), state.buildingId())
                && Objects.equals(indicator.getEquipId(), state.equipId())
                && state.status() != null;
    }

    /** 校验 TDengine 成功行的指标、建筑和设备身份与当前 MySQL 配置完全一致。 */
    private boolean successMatches(
            BizIndicator indicator, IndicatorMinuteResult row) {
        return Objects.equals(indicator.getIndicatorId(), row.indicatorId())
                && Objects.equals(indicator.getIndicatorCode(), row.indicatorCode())
                && Objects.equals(indicator.getBuildingId(), row.buildingId())
                && Objects.equals(indicator.getEquipId(), row.equipId());
    }

    /** 校验 TDengine 异常行的指标、建筑和设备身份与当前 MySQL 配置完全一致。 */
    private boolean exceptionMatches(
            BizIndicator indicator, FormulaCalculationException row) {
        return Objects.equals(indicator.getIndicatorId(), row.indicatorId())
                && Objects.equals(indicator.getIndicatorCode(), row.indicatorCode())
                && Objects.equals(indicator.getBuildingId(), row.buildingId())
                && Objects.equals(indicator.getEquipId(), row.equipId());
    }

    /**
     * 过滤身份不匹配的 TDengine 成功行，并为每个缺失指标选出最新一次成功。
     * 返回映射随后与异常映射合并为最新状态。
     */
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

    /**
     * 过滤身份不匹配的 TDengine 异常行，并为每个缺失指标选出最新一次失败。
     * 返回映射随后与成功映射合并为最新状态。
     */
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

    /**
     * 在同一指标的重复成功行中选择业务上更新的一条：先比较来源分钟，
     * 同一分钟再比较计算时间，以保留最近一次补算结果。
     */
    private IndicatorMinuteResult newerSuccess(
            IndicatorMinuteResult left, IndicatorMinuteResult right) {
        if (left.minuteStart() != right.minuteStart()) {
            return left.minuteStart() > right.minuteStart() ? left : right;
        }
        return left.calculatedAt() >= right.calculatedAt() ? left : right;
    }

    /**
     * 在同一指标的重复异常行中选择业务上更新的一条：先比较来源分钟，
     * 同一分钟再比较计算时间，以保留最近一次失败审计。
     */
    private FormulaCalculationException newerException(
            FormulaCalculationException left,
            FormulaCalculationException right) {
        if (left.minuteStart() != right.minuteStart()) {
            return left.minuteStart() > right.minuteStart() ? left : right;
        }
        return left.calculatedAt() >= right.calculatedAt() ? left : right;
    }

    /**
     * 合并一个指标在 TDengine 中最新的成功与异常事实。
     *
     * <p>同一分钟同时存在时表示后续补算已经成功，成功优先；不同分钟则取来源分钟
     * 较新的状态。两者都没有时返回 {@code NO_DATA}。</p>
     */
    private HvacIndicatorDtos.LatestIndicator mergeLatest(
            BizIndicator indicator,
            IndicatorMinuteResult success,
            FormulaCalculationException exception) {
        // 同一分钟的成功表示补算已修复旧异常，不能再向前端暴露失败状态。
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

    /**
     * 将已通过身份校验的 Redis 最新状态转换为列表 DTO。
     * 只有成功状态携带数值和质量，失败状态保留原因与缺失输入。
     */
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

    /** 将 TDengine 成功结果转换为最新指标 DTO，并补充配置对应的展示单位。 */
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

    /** 将 TDengine 失败审计转换为无数值的最新指标 DTO，保留原因和缺失输入。 */
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

    /** 为已配置但 Redis、TDengine 均无结果的指标生成稳定 {@code NO_DATA} 槽位。 */
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

    /**
     * 将 Redis 中同一分钟的最新状态转换为计算详情。
     * 缓存保存的输入和步骤会原样返回，失败状态不暴露无效数值与质量。
     */
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

    /**
     * 将指定历史公式版本的重放结果转换为计算详情。
     * 成功时返回值和质量，失败时保留重放得到的原因、输入、步骤及缺失项。
     */
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

    /**
     * 将 TDengine 持久化异常转换为计算详情。
     * 异常事实只保存原因和缺失输入，因此输入与步骤返回空列表而不是伪造过程。
     */
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

    /** 为合法指标但指定分钟无缓存、成功或异常记录的情况生成 {@code NO_DATA} 详情。 */
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

    /**
     * 将四类稳定指标编码映射为接口展示单位。
     * COP 无量纲返回 {@code null}，未知编码也不猜测单位。
     */
    private String unit(String indicatorCode) {
        return switch (indicatorCode) {
            case "WCR_COP" -> null;
            case "TOWER_EFF", "PUMP_EFF" -> "%";
            case "AHU_POW_EFF" -> "W/(m³/h)";
            default -> null;
        };
    }

    /**
     * 执行指标或分钟 Repository 查询并稳定外部资源失败语义。
     *
     * <p>只将 JDBC/TDengine 的 {@link DataAccessException} 转换为可重试的 503，
     * 不吞掉权限、MySQL 配置和公式版本等业务异常。</p>
     */
    private <T> T queryTdengine(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException exception) {
            // 不把 JDBC 地址、SQL 或凭据细节返回给调用方，统一映射为可重试的 503。
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
