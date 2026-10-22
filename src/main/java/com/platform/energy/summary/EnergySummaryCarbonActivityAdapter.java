package com.platform.energy.summary;

import com.platform.carbon.CarbonActivityInputPort;
import com.platform.carbon.CarbonEvidence;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.carbon.CarbonCalculationCore;
import com.platform.carbon.CarbonProjectService;
import com.platform.carbon.CarbonModels.ActivitySegment;
import com.platform.carbon.CarbonModels.PeriodType;
import com.platform.carbon.CarbonModels.ResultNature;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.period.EnergyPeriodSnapshotReader;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryGroupView;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;

import static com.platform.carbon.CarbonErrors.DEPENDENCY_UNAVAILABLE;
import static com.platform.carbon.CarbonErrors.LIMIT_EXCEEDED;
import static com.platform.carbon.CarbonErrors.error;

@Component
/** 将第七闭环的受控汇总查询适配为碳模块可固定版本的活动段。 */
public class EnergySummaryCarbonActivityAdapter implements CarbonActivityInputPort {
    private static final long SYSTEM_ACTOR_ID = 0L;
    private static final List<String> SYSTEM_ROLES = List.of("PLATFORM_ADMIN");
    private static final long MAX_PROJECT_EVIDENCE_BYTES = 16L * 1024 * 1024;

    private final EnergyBoundarySummaryService summaryService;
    private final EnergyPeriodSnapshotReader snapshotReader;
    private final CarbonProjectService projects;

    @Autowired
    public EnergySummaryCarbonActivityAdapter(EnergyBoundarySummaryService summaryService,
                                              EnergyPeriodSnapshotReader snapshotReader,
                                              CarbonProjectService projects) {
        this.summaryService=summaryService;this.snapshotReader=snapshotReader;this.projects=projects;
    }
    EnergySummaryCarbonActivityAdapter(EnergyBoundarySummaryService summaryService,EnergyPeriodSnapshotReader snapshotReader) {
        this(summaryService,snapshotReader,null);
    }

    @Override
    public List<ActivitySegment> read(String buildingId, PeriodType periodType,
                                      Instant startInclusive, Instant endExclusive, int limit) {
        // 当前能源公共服务只发布月度封账；季年核算保留真实月段，不读取未封账的年度投影。
        String upstreamPeriodType = "MONTH";
        var summary = summaryService.query(SYSTEM_ACTOR_ID, SYSTEM_ROLES,
                new SummaryQueryRequest(buildingId, upstreamPeriodType, startInclusive,
                        endExclusive, List.of("ENERGY_ITEM", "PERIOD")));
        List<PeriodSnapshot> snapshots = snapshotReader.listVisibleSnapshots(buildingId,
                upstreamPeriodType, startInclusive, endExclusive, limit + 1);
        if (snapshots.size() > limit) {
            throw error(409, LIMIT_EXCEEDED, "碳活动数据快照超过单次计算上限");
        }
        if (summary.sourceSnapshotCount() != snapshots.size()) {
            throw error(409, DEPENDENCY_UNAVAILABLE,
                    "计量边界汇总与封账快照版本不一致，请重新发起计算");
        }
        List<ActivitySegment> result = new ArrayList<>();
        long projectEvidenceBytes = 0;
        for (SummaryGroupView group : summary.groups()) {
            String itemCode = group.groupKey().get("ENERGY_ITEM");
            Instant[] period = period(group.groupKey().get("PERIOD"));
            Set<String> evidence = Set.copyOf(group.evidenceHashes());
            List<PeriodSnapshot> matched = snapshots.stream()
                    .filter(value -> value.energyItemCode().equals(itemCode))
                    .filter(value -> value.startInclusive().equals(period[0])
                            && value.endExclusive().equals(period[1]))
                    .filter(value -> evidence.contains(value.evidenceHash())).toList();
            if (matched.isEmpty() || !matched.stream().map(PeriodSnapshot::evidenceHash)
                    .collect(Collectors.toSet()).equals(evidence)) {
                throw error(409, DEPENDENCY_UNAVAILABLE,
                        "计量边界汇总与封账快照版本不一致，请重新发起计算");
            }
            String timezone = common(matched.stream().map(PeriodSnapshot::timezoneId).toList(),
                    "封账快照时区不一致");
            ObjectNode trace = trace(group, matched);
            if (group.grossInboundQuantities().size() != 1) {
                throw error(409, DEPENDENCY_UNAVAILABLE,
                        "同一能源品种和周期必须只有一个权威活动量单位");
            }
            for (var quantity : group.grossInboundQuantities().entrySet()) {
                if (quantity.getValue().signum() < 0) {
                    throw error(409, DEPENDENCY_UNAVAILABLE, "权威活动量不得为负数");
                }
                // 权威汇总的证据可能只列总表；分解必须固定同期间所有组成快照，不能遗漏分表。
                var composition=snapshots.stream().filter(s->itemCode.equals(s.energyItemCode())
                        &&quantity.getKey().equals(s.nativeUnitCode())&&period[0].equals(s.startInclusive())
                        &&period[1].equals(s.endExclusive())).toList();
                var projection=projects==null?null:projects.project(buildingId,period[0],period[1],itemCode,quantity.getKey(),composition);
                if(projection==null) add(result,trace,buildingId,period,timezone,itemCode,quantity.getValue(),quantity.getKey(),group);
                else {
                    if(projection.source().total().compareTo(quantity.getValue())!=0)throw error(409,DEPENDENCY_UNAVAILABLE,"分类计量组成与权威入量不一致，不能替换碳活动量");
                    trace.set("projectVersion",CarbonEvidence.tree(projection.version()));
                    trace.set("compositionSnapshots",trace(group,composition).get("sources"));
                    trace.set("classificationConfiguration",CarbonEvidence.tree(projection.source().configuration()));
                    trace.set("excludedSources",CarbonEvidence.tree(projection.applied().excludedSlices()));
                    trace.put("grossQuantity",quantity.getValue().toPlainString());
                    trace.put("excludedQuantity",projection.applied().excluded().toPlainString());
                    trace.put("projectEnergyPathway",projection.version().configuration().energies().stream().filter(e->"ENABLED".equals(e.capability())&&e.energyItemCode().equals(itemCode)).findFirst().orElseThrow().pathway());
                    // 每个活动段独立固定证据，展开前限制总字节，避免大分类表乘以组成数耗尽内存。
                    long traceBytes=CarbonEvidence.canonical(trace).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    long copies=Math.max(1,projection.applied().includedSlices().size());
                    if(traceBytes>(MAX_PROJECT_EVIDENCE_BYTES-projectEvidenceBytes)/copies)
                        throw error(409,LIMIT_EXCEEDED,"本次项目组成证据超过16MiB上限，请缩小计算范围或分类配置");
                    long sliceBytes=projection.applied().includedSlices().stream()
                            .mapToLong(s->CarbonEvidence.canonical(CarbonEvidence.tree(s)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
                    if(sliceBytes>MAX_PROJECT_EVIDENCE_BYTES-projectEvidenceBytes-traceBytes*copies)
                        throw error(409,LIMIT_EXCEEDED,"本次项目组成证据超过16MiB上限，请缩小计算范围或分类配置");
                    projectEvidenceBytes+=traceBytes*copies+sliceBytes;
                    for(var slice:projection.applied().includedSlices()) {
                        ObjectNode sourceTrace=trace.deepCopy();sourceTrace.set("sourceSlice",CarbonEvidence.tree(slice));
                        add(result,sourceTrace,buildingId,period,timezone,itemCode,slice.quantity(),quantity.getKey(),group);
                    }
                    if(projection.applied().includedSlices().isEmpty()) {
                        trace.put("activityStatus","EXCLUDED");
                        add(result,trace,buildingId,period,timezone,itemCode,java.math.BigDecimal.ZERO,quantity.getKey(),group);
                    }
                }
            }
        }
        if (result.size() > limit) {
            throw error(409, LIMIT_EXCEEDED, "碳活动数据明细超过单次计算上限");
        }
        return List.copyOf(result);
    }

    private static void add(List<ActivitySegment> values,ObjectNode trace,String building,Instant[] period,
                            String timezone,String energy,java.math.BigDecimal quantity,String unit,SummaryGroupView group) {
        String hash=CarbonCalculationCore.sha256(CarbonEvidence.canonical(trace));
        values.add(new ActivitySegment(hash.substring(0,32),building,PeriodType.MONTH,period[0],period[1],timezone,
                energy,quantity,unit,group.lockStatus(),group.resultCompleteness(),
                "FORMAL".equals(group.resultNature())?ResultNature.FORMAL:ResultNature.DEVELOPMENT_SIMULATION,hash,trace));
    }

    /** 同时固定权威汇总与组成快照；历史重算后不再依赖当前可见版本反查旧结果。 */
    private static ObjectNode trace(SummaryGroupView group, List<PeriodSnapshot> snapshots) {
        ObjectNode trace = CarbonEvidence.object();
        trace.put("contractVersion", "ENERGY_SUMMARY_CARBON_V1");
        trace.set("relationVersionIds", CarbonEvidence.tree(group.relationVersionIds().stream().sorted().toList()));
        trace.set("summaryPolicyVersionIds", CarbonEvidence.tree(group.summaryPolicyVersionIds().stream().sorted().toList()));
        ObjectNode summary = (ObjectNode) CarbonEvidence.tree(group);
        summary.set("relationVersionIds", trace.get("relationVersionIds"));
        summary.set("summaryPolicyVersionIds", trace.get("summaryPolicyVersionIds"));
        summary.set("conversionRuleVersionIds", CarbonEvidence.tree(group.conversionRuleVersionIds().stream().sorted().toList()));
        summary.set("evidenceHashes", CarbonEvidence.tree(group.evidenceHashes().stream().sorted().toList()));
        trace.set("summary", summary);
        var sources = trace.putArray("sources");
        snapshots.stream().sorted(Comparator.comparing(PeriodSnapshot::snapshotId)).forEach(snapshot -> {
            ObjectNode source = (ObjectNode) CarbonEvidence.tree(snapshot);
            source.remove("evidenceJson");
            source.set("evidence", CarbonEvidence.parse(snapshot.evidenceJson()));
            sources.add(source);
        });
        return trace;
    }

    private static Instant[] period(String value) {
        try {
            String[] parts = value.split("/", 2);
            return new Instant[]{Instant.parse(parts[0]), Instant.parse(parts[1])};
        } catch (RuntimeException exception) {
            throw error(409, DEPENDENCY_UNAVAILABLE, "计量边界汇总周期证据无效");
        }
    }

    private static String common(List<String> values, String message) {
        if (values.isEmpty() || values.stream().distinct().count() != 1) {
            throw error(409, DEPENDENCY_UNAVAILABLE, message);
        }
        return values.getFirst();
    }
}
