package com.platform.energy.efficiency;

import com.platform.energy.aggregation.NativeQuantityAggregationService;
import com.platform.energy.aggregation.EnergyAggregationModels.ActivityFact;
import com.platform.energy.period.NativePeriodSnapshotService.*;
import com.platform.iot.calculation.CalculationPointReadService;
import com.platform.iot.calculation.CalculationPointReadContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.platform.energy.aggregation.NativeQuantityAggregationService.*;
import static com.platform.energy.efficiency.EerpContracts.*;
import static com.platform.energy.efficiency.EerpSupport.*;

/** 在同一水位固定全部实际输入，再按配置选择唯一来源；失败输入保留为不可计算区间。 */
@Service
@RequiredArgsConstructor
public class EerpPeriodCalculator {
    public record Execution(String resultJson, String evidenceJson, List<Snapshot> numericSnapshots) {}
    private final CalculationPointReadService pointReader;
    private final NativeQuantityAggregationService aggregation;
    private final EerpSupport codec;

    public Execution calculate(long user, Collection<String> roles, String taskId, ConfigView config,
            PeriodRequest request, Runnable budgetCheck) {
        Configuration c = config.configuration();
        Set<String> points = new LinkedHashSet<>();
        for (CoolingSource source : c.coolingSources()) {
            if (source.mode() == SourceMode.METER_CUMULATIVE) points.add(source.meter().pointId());
            else { points.add(source.flow().pointId()); points.add(source.supply().pointId()); points.add(source.returnTemperature().pointId()); }
        }
        for (ElectricitySource source : c.electricitySources()) points.add(source.meter().pointId());
        budgetCheck.run();
        long lookback = c.coolingSources().stream().filter(s -> s.mode()==SourceMode.FLOW_TEMPERATURE
                && s.rules().alignmentMode()==CoolingComputationCore.AlignmentMode.BOUNDED_PREVIOUS_HOLD)
                .mapToLong(s -> s.rules().maxHoldMillis()).max().orElse(0);
        // 读取包含结束时刻的锚点；累计差值需要该读数，积分本身仍严格使用半开区间。
        var input = pointReader.read(user,roles,c.buildingId(),points,request.fromInclusive().minusMillis(lookback),request.toExclusive(),request.asOf());
        budgetCheck.run();
        List<Measure> measures = new ArrayList<>(); List<Issue> issues = new ArrayList<>();
        List<Snapshot> numeric = new ArrayList<>(); Map<String,Object> evidence = new LinkedHashMap<>();
        evidence.put("config",config); evidence.put("input",input);
        evidence.put("unitNormalizationVersion","EERP_CANONICAL_ALIASES_V1");
        evidence.put("calculationVersion","WATER_FIXED_RHO_CP_STEP_MILLIS_V1");
        evidence.put("arithmeticContext","DECIMAL128");
        for (CoolingSource source : c.coolingSources()) {
            budgetCheck.run();
            if (source.mode() == SourceMode.METER_CUMULATIVE) {
                measures.add(cumulative(user,roles,taskId,c,request,input,source.sourceId(),source.meter(),
                        source.coveredChillerIds(),"COOLING_ENERGY",evidence,numeric));
            } else {
                validateMetadata(source.flow(),input); validateMetadata(source.supply(),input); validateMetadata(source.returnTemperature(),input);
                var calculated = new CoolingComputationCore().calculate(request.fromInclusive(),request.toExclusive(),
                        samples(input,source.flow().pointId()),samples(input,source.supply().pointId()),
                        samples(input,source.returnTemperature().pointId()),source.rules());
                evidence.put(source.sourceId(),calculated);
                var intervals = calculated.powerIntervals().stream().map(p -> new NativePowerInterval(
                        source.sourceId()+":"+p.startInclusive().toEpochMilli(),p.startInclusive(),p.endExclusive(),p.powerKw(),
                        p.inputs().stream().map(CoolingComputationCore.InputEvidence::sampleId).toList(),codec.json(p.inputs()))).toList();
                var integrated = aggregation.integratePower(new NativeIntegrationRequest(scope(c,source.flow().pointId(),request,input),"kWh",intervals));
                evidence.put(source.sourceId()+":integration",integrated);
                boolean hasValidInterval = integrated.coverageRatio().signum()>0;
                String snapshotId = hasValidInterval ? snapshotId(taskId,source.sourceId()) : null;
                List<String> reasons = new ArrayList<>(integrated.issues());
                for (var issue : calculated.issues()) {
                    reasons.add(issue.code()); issues.add(new Issue(issue.code(),source.sourceId(),issue.startInclusive(),issue.endExclusive()));
                }
                measures.add(new Measure(source.sourceId(),"COOLING_ENERGY",source.coveredChillerIds(),hasValidInterval ? integrated.quantity() : null,
                        integrated.coverageRatio(),calculated.complete() && integrated.complete(),snapshotId,List.copyOf(new LinkedHashSet<>(reasons))));
                if (hasValidInterval) numeric.add(snapshot(snapshotId,c,source.sourceId(),"COOLING_ENERGY","kWh",request.fromInclusive(),integrated.quantity(),integrated.coverageRatio(),codec.json(calculated)));
                if (!calculated.powerIntervals().isEmpty()) {
                    numeric.add(new Snapshot(snapshotId+"p",c.buildingId(),source.sourceId(),"COOLING_POWER","kW",hash(codec.json(calculated)),
                            calculated.powerIntervals().stream().map(p -> new NumericSample(p.startInclusive(),p.powerKw(),BigDecimal.ONE)).toList()));
                }
            }
        }
        for (ElectricitySource source : c.electricitySources()) {
            budgetCheck.run();
            measures.add(cumulative(user,roles,taskId,c,request,input,source.sourceId(),source.meter(),
                    source.coveredDeviceIds(),"ELECTRICITY_ENERGY",evidence,numeric));
        }
        for (Measure m : measures) for (String reason : m.reasons())
            issues.add(new Issue(reason,m.sourceId(),request.fromInclusive(),request.toExclusive()));
        var result = new PeriodResult(config.versionId(),c.buildingId(),c.stationId(),request.fromInclusive(),request.toExclusive(),request.asOf(),
                c.timezoneId(),c.timezoneVersion(),sum(measures,"COOLING_ENERGY"),sum(measures,"ELECTRICITY_ENERGY"),
                measures.stream().allMatch(Measure::complete),List.copyOf(measures),List.copyOf(issues),"DEVELOPMENT_SIMULATION",
                input.facts().stream().map(CalculationPointFact::sourceNature).distinct().count()==1
                        ? input.facts().getFirst().sourceNature() : "UNKNOWN_OR_MIXED");
        return new Execution(codec.json(result),codec.json(evidence),List.copyOf(numeric));
    }
    private Measure cumulative(long user, Collection<String> roles, String task, Configuration c, PeriodRequest request,
            CalculationPointSnapshot input, String source, Point point, Set<String> coverage, String type,
            Map<String,Object> evidence,List<Snapshot> numeric) {
        validateMetadata(point,input);
        var rows = input.facts().stream().filter(f -> point.pointId().equals(f.pointId())
                && !f.eventTime().isBefore(request.fromInclusive()) && !f.eventTime().isAfter(request.toExclusive())).toList();
        var governed = aggregation.governedEvidence(user,roles,c.buildingId(),point.pointId(),request.fromInclusive(),request.toExclusive());
        evidence.put(source+":governed",governed);
        // 累计量中的被阻断事实也不能被静默过滤；完整性失败仍保留整组原始与修正证据。
        if (rows.stream().anyMatch(f -> !allowed(f))) return new Measure(source,type,coverage,null,BigDecimal.ZERO,false,null,List.of("QUALITY_BLOCKED"));
        var facts = rows.stream().map(f -> new ActivityFact(f.factIdentity(),BigDecimal.valueOf(f.rawValue()),f.eventTime(),f.receivedTime(),
                f.qualityLevel(),String.valueOf(f.policyVersion()),false,null,null,null,null)).toList();
        NativeQuantityResult value;
        try {
            value = aggregation.aggregateCumulative(new NativeCumulativeRequest(scope(c,point.pointId(),request,input),"kWh",facts,governed.meterEvents(),governed.corrections()));
        } catch (com.platform.framework.exception.BusinessException invalidInput) {
            if (!Integer.valueOf(409).equals(invalidInput.getCode())) throw invalidInput;
            String reason = invalidInput.getErrorCode()==null?"CUMULATIVE_INPUT_INVALID":invalidInput.getErrorCode();
            evidence.put(source+":aggregation",Map.of("rejectionCode",reason));
            return new Measure(source,type,coverage,null,BigDecimal.ZERO,false,null,List.of(reason));
        }
        evidence.put(source+":aggregation",value);
        String snapshotId = value.quantity() == null ? null : snapshotId(task,source);
        if (snapshotId != null) numeric.add(snapshot(snapshotId,c,source,type,"kWh",request.fromInclusive(),value.quantity(),value.coverageRatio(),codec.json(value)));
        return new Measure(source,type,coverage,value.quantity(),value.coverageRatio(),value.complete(),snapshotId,value.issues());
    }
    private void validateMetadata(Point expected, CalculationPointSnapshot snapshot) {
        var actual = snapshot.points().stream().filter(p -> expected.pointId().equals(p.pointId())).findFirst()
                .orElseThrow(() -> error(409,"POINT_MISMATCH","计算读取未返回必需测点"));
        require(expected.dataType().equals(actual.dataType()) && expected.unit().equals(actual.unit()),"POINT_MISMATCH","测点档案已偏离审核版本");
    }
    private List<CoolingComputationCore.Sample> samples(CalculationPointSnapshot input,String point) {
        return input.facts().stream().filter(f -> point.equals(f.pointId())).map(f -> new CoolingComputationCore.Sample(
                f.factIdentity(),f.eventTime(),BigDecimal.valueOf(f.rawValue()),allowed(f),codec.json(f))).toList();
    }
    private static boolean allowed(CalculationPointFact fact) { return "ALLOW".equals(String.valueOf(fact.decision())); }
    private static NativeScope scope(Configuration c,String point,PeriodRequest request,CalculationPointSnapshot input) {
        return new NativeScope(c.buildingId(),point,request.fromInclusive(),request.toExclusive(),request.asOf(),input.activityWatermark());
    }
    private static BigDecimal sum(List<Measure> values,String type) {
        return values.stream().filter(v -> type.equals(v.quantityType())).map(Measure::quantityKwh).filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
    }
    private static String snapshotId(String task,String source) { return task+hash(source).substring(0,16); }
    private static Snapshot snapshot(String id,Configuration c,String source,String type,String unit,Instant at,
            BigDecimal value,BigDecimal coverage,String evidence) {
        return new Snapshot(id,c.buildingId(),source,type,unit,hash(evidence),List.of(new NumericSample(at,value,coverage)));
    }
}
