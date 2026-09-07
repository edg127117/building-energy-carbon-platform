package com.platform.energy.aggregation;

import com.platform.energy.aggregation.EnergyAggregationCore.NativeCumulativeComputation;
import com.platform.energy.aggregation.EnergyAggregationCore.NativePowerIntegration;
import com.platform.energy.aggregation.EnergyAggregationCore.NativePowerSegment;
import com.platform.energy.aggregation.EnergyAggregationModels.ActivityFact;
import com.platform.energy.aggregation.EnergyAggregationModels.CorrectionEvidence;
import com.platform.energy.aggregation.EnergyAggregationModels.MeterEventEvidence;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static com.platform.energy.aggregation.EnergyAggregationErrors.INPUT_INCOMPLETE;
import static com.platform.energy.aggregation.EnergyAggregationErrors.error;

/**
 * 原生量的公共聚合入口，不要求能源分类、折标参数或研发模拟身份。
 *
 * <p>累计量仍使用活动事实、计量事件和修正的既有治理证据；功率积分只接受已固定的有效半开
 * 区间，缺口保留为不完整结果而不是由两端数值补齐。</p>
 */
@Service
public class NativeQuantityAggregationService {
    private static final int MAX_GOVERNED_EVIDENCE_PER_TYPE = 1_000;
    private final EnergyAggregationCore core;
    private final EnergyAggregationAuthorization authorization;
    private final EnergyAggregationGovernanceService governanceService;

    public NativeQuantityAggregationService(
            EnergyAggregationCore core,
            EnergyAggregationAuthorization authorization,
            EnergyAggregationGovernanceService governanceService) {
        this.core = core;
        this.authorization = authorization;
        this.governanceService = governanceService;
    }

    /**
     * 在读取冻结快照前取得同一建筑、测点和时间范围内已审核的计量治理证据。
     *
     * <p>授权检查在这里完成，调用方不得自行构造“已审核”事件或修正来跳过治理链。</p>
     */
    public GovernedEvidence governedEvidence(
            long userId,
            Collection<String> roles,
            String buildingId,
            String pointId,
            Instant fromInclusive,
            Instant toExclusive) {
        validateReadScope(buildingId, pointId, fromInclusive, toExclusive);
        authorization.requireRunner(userId, roles);
        authorization.checkBuilding(userId, roles, buildingId);
        List<MeterEventEvidence> events = governanceService
                .approvedEvents(buildingId, pointId, fromInclusive, toExclusive,
                        MAX_GOVERNED_EVIDENCE_PER_TYPE + 1);
        List<CorrectionEvidence> corrections = governanceService
                .approvedCorrections(buildingId, pointId, MAX_GOVERNED_EVIDENCE_PER_TYPE + 1);
        if (events.size() > MAX_GOVERNED_EVIDENCE_PER_TYPE
                || corrections.size() > MAX_GOVERNED_EVIDENCE_PER_TYPE) {
            throw error(INPUT_INCOMPLETE, "已审核计量事件或修正超过原生量单次读取上限");
        }
        return new GovernedEvidence(events, corrections);
    }

    /** 按固定水位和精确边界聚合累计原生量。 */
    public NativeQuantityResult aggregateCumulative(NativeCumulativeRequest request) {
        if (request == null) throw error(INPUT_INCOMPLETE, "原生累计量请求不能为空");
        validateScope(request.scope());
        String unitCode = requiredUnit(request.nativeUnitCode());
        NativeCumulativeComputation value = core.aggregateNativeCumulative(
                request.scope().buildingId(), request.scope().pointId(), request.scope().fromInclusive(),
                request.scope().toExclusive(), request.scope().calculationAsOf(),
                request.scope().activityWatermark(), request.facts(), request.meterEvents(), request.corrections());
        return new NativeQuantityResult(value.quantity(), unitCode, BigDecimal.ONE,
                value.maximumObservedGapMillis(), value.inputFactIds(), value.meterEventVersionIds(),
                value.correctionVersionIds(), List.of(), true);
    }

    /** 对已确认有效的原生功率半开区间执行阶梯积分。 */
    public NativeQuantityResult integratePower(NativeIntegrationRequest request) {
        if (request == null) throw error(INPUT_INCOMPLETE, "原生功率积分请求不能为空");
        validateScope(request.scope());
        String unitCode = requiredUnit(request.nativeUnitCode());
        if (request.intervals() == null) throw error(INPUT_INCOMPLETE, "原生功率区间不能为空");
        List<NativePowerSegment> segments = request.intervals().stream().map(interval -> {
            if (interval == null) throw error(INPUT_INCOMPLETE, "原生功率区间不能为空");
            return new NativePowerSegment(interval.intervalId(), interval.startInclusive(), interval.endExclusive(),
                    interval.powerKw(), interval.inputFactIds(), interval.qualityEvidence());
        }).toList();
        NativePowerIntegration value = core.integrateNativePower(
                request.scope().fromInclusive(), request.scope().toExclusive(), segments);
        return new NativeQuantityResult(value.quantity(), unitCode, value.coverageRatio(),
                value.maximumObservedGapMillis(), value.inputFactIds(), List.of(), List.of(),
                value.issueCodes(), value.complete());
    }

    private static void validateReadScope(
            String buildingId, String pointId, Instant fromInclusive, Instant toExclusive) {
        if (blank(buildingId) || blank(pointId) || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive)) {
            throw error(INPUT_INCOMPLETE, "治理证据读取范围无效");
        }
    }

    private static void validateScope(NativeScope scope) {
        if (scope == null) throw error(INPUT_INCOMPLETE, "原生量范围不能为空");
        validateReadScope(scope.buildingId(), scope.pointId(), scope.fromInclusive(), scope.toExclusive());
        if (scope.calculationAsOf() == null || scope.activityWatermark() == null
                || scope.calculationAsOf().isBefore(scope.toExclusive())
                || scope.activityWatermark().isAfter(scope.calculationAsOf())) {
            throw error(INPUT_INCOMPLETE, "原生量水位或计算时点无效");
        }
    }

    private static String requiredUnit(String unitCode) {
        if (blank(unitCode)) throw error(INPUT_INCOMPLETE, "原生量单位不能为空");
        return unitCode;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 一次冻结读取所固定的对象、半开范围、计算时点和活动事实水位。 */
    public record NativeScope(
            String buildingId,
            String pointId,
            Instant fromInclusive,
            Instant toExclusive,
            Instant calculationAsOf,
            Instant activityWatermark) {
    }

    /** 原生累计量输入；事实必须已按 {@link NativeScope} 的单个测点范围读取。 */
    public record NativeCumulativeRequest(
            NativeScope scope,
            String nativeUnitCode,
            List<ActivityFact> facts,
            List<MeterEventEvidence> meterEvents,
            List<CorrectionEvidence> corrections) {
        public NativeCumulativeRequest {
            facts = facts == null ? null : List.copyOf(facts);
            meterEvents = meterEvents == null ? null : List.copyOf(meterEvents);
            corrections = corrections == null ? null : List.copyOf(corrections);
        }
    }

    /** 已审核计量事件和修正的不可变读取结果。 */
    public record GovernedEvidence(
            List<MeterEventEvidence> meterEvents,
            List<CorrectionEvidence> corrections) {
        public GovernedEvidence {
            meterEvents = List.copyOf(meterEvents);
            corrections = List.copyOf(corrections);
        }
    }

    /** 已计算功率的有效半开区间；功率单位固定为 kW。 */
    public record NativePowerInterval(
            String intervalId,
            Instant startInclusive,
            Instant endExclusive,
            BigDecimal powerKw,
            List<String> inputFactIds,
            String qualityEvidence) {
        public NativePowerInterval {
            inputFactIds = inputFactIds == null ? null : List.copyOf(inputFactIds);
        }
    }

    /** 原生功率积分输入；本入口不做梯形插值或跨缺口补值。 */
    public record NativeIntegrationRequest(
            NativeScope scope,
            String nativeUnitCode,
            List<NativePowerInterval> intervals) {
        public NativeIntegrationRequest {
            intervals = intervals == null ? null : List.copyOf(intervals);
        }
    }

    /** 原生数量、覆盖率和已使用证据；{@code complete=false} 时仍保留可积分的部分数量。 */
    public record NativeQuantityResult(
            BigDecimal quantity,
            String unitCode,
            BigDecimal coverageRatio,
            long maximumObservedGapMillis,
            List<String> inputFactIds,
            List<String> meterEventVersionIds,
            List<String> correctionVersionIds,
            List<String> issues,
            boolean complete) {
        public NativeQuantityResult {
            inputFactIds = List.copyOf(inputFactIds);
            meterEventVersionIds = List.copyOf(meterEventVersionIds);
            correctionVersionIds = List.copyOf(correctionVersionIds);
            issues = List.copyOf(issues);
        }
    }
}
