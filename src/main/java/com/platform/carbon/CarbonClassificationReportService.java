package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.platform.carbon.CarbonModels.StoredCalculationItem;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import static com.platform.carbon.CarbonErrors.*;

/** 只汇总已存批次的固定明细与分类证据，不读取当前分类或按比例重算碳值。 */
@Service
public class CarbonClassificationReportService {
    private final CarbonCalculationService calculations;
    private final CarbonCalculationRepository repository;
    public CarbonClassificationReportService(CarbonCalculationService calculations,CarbonCalculationRepository repository){this.calculations=calculations;this.repository=repository;}
    public record Node(String schemeCode,String classificationVersionId,String nodeCode,String nodeName,String energyItemCode,
                       String activityUnit,boolean additive,
                       @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal activityQuantity,
                       @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal emissionKgCo2e) { }
    public record Period(String startInclusive,String scopeType,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal emissionKgCo2e) { }
    public record Report(String buildingId,String batchId,String status,String unit,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalKgCo2e,
                         boolean classificationComplete,List<Node> nodes,List<Period> periods,
                         List<JsonNode> excludedSources,List<String> warnings,List<CarbonClassificationFlow.Graph> flows) { }
    public Report report(long user,Collection<String> roles,String batchId) {
        var detail=calculations.detail(user,roles,batchId);
        if(!Set.of("COMPLETED_COMPLETE","COMPLETED_INCOMPLETE").contains(detail.batch().status()))
            return new Report(detail.batch().buildingId(),batchId,detail.batch().status(),"KG_CO2E",null,false,
                    List.of(),List.of(),List.of(),List.of("该批次尚未完成可用计算，排放量不可用"),List.of());
        if(detail.items().size()>5000)throw error(409,LIMIT_EXCEEDED,"分类碳报表超过5000条明细上限");
        // 批次授权通过后有界读取证据，避免每个原子组成重复鉴权与访问数据库。
        String shared=repository.sharedEvidence(batchId);
        Map<String,CarbonCalculationRepository.StoredEvidence> evidence=new HashMap<>();
        for(var stored:repository.listEvidence(batchId)) {
            if(evidence.put(stored.itemId(),stored)!=null)throw error(409,DEPENDENCY_UNAVAILABLE,"批次明细证据重复");
        }
        if(evidence.size()!=detail.items().size())throw error(409,DEPENDENCY_UNAVAILABLE,"批次明细证据不完整");
        List<Entry> entries=new ArrayList<>();
        for(var item:detail.items()) {
            var stored=evidence.get(item.calculationItemId());
            if(stored==null)throw error(409,DEPENDENCY_UNAVAILABLE,"批次明细证据缺失");
            entries.add(new Entry(item,CarbonEvidence.stored(stored.json(),stored.hash(),shared)));
        }
        Report result=aggregate(detail.batch().buildingId(),batchId,detail.batch().status(),entries);
        if(shared==null)return result;
        List<JsonNode> excluded=new ArrayList<>(result.excludedSources());
        for(JsonNode stored:CarbonEvidence.parse(shared).path("excludedActivities")) {
            var activity=stored.path("evidence");
            if(!CarbonCalculationCore.sha256(CarbonEvidence.canonical(activity)).equals(stored.path("evidenceHash").asText()))
                throw error(409,DEPENDENCY_UNAVAILABLE,"排除来源证据摘要不一致");
            var record=CarbonEvidence.object();record.put("status","EXCLUDED");record.set("projectVersion",activity.path("projectVersion"));
            record.set("sources",activity.path("excludedSources"));record.set("grossQuantity",activity.path("grossQuantity"));
            if("ENERGY_BILL_CARBON_V1".equals(activity.path("contractVersion").asText())) {
                record.set("source",activity.path("billSource"));record.set("excludedQuantity",activity.path("excludedQuantity"));
            }
            excluded.add(record);
        }
        return new Report(result.buildingId(),result.batchId(),result.status(),result.unit(),result.totalKgCo2e(),result.classificationComplete(),result.nodes(),result.periods(),List.copyOf(excluded),result.warnings(),result.flows());
    }
    record Entry(StoredCalculationItem item,JsonNode evidence) { }
    private record Key(String scheme,String version,String node,String energy,String unit) { }
    private record PeriodKey(String start,String scope) { }
    private static final class Accumulator {
        BigDecimal quantity=BigDecimal.ZERO,emission=BigDecimal.ZERO;String name;boolean additive;
    }
    static Report aggregate(String building,String batch,String status,List<Entry> entries) {
        Map<Key,Accumulator> nodes=new LinkedHashMap<>();Map<PeriodKey,BigDecimal> periods=new LinkedHashMap<>();
        Map<String,JsonNode> excluded=new LinkedHashMap<>();Set<String> warnings=new LinkedHashSet<>();
        BigDecimal total=BigDecimal.ZERO;boolean complete="COMPLETED_COMPLETE".equals(status);
        for(Entry entry:entries) {
            StoredCalculationItem item=entry.item();
            // 明细表为18位小数；分类汇总保留已冻结的未舍入结果，不重新运行任何因子公式。
            BigDecimal amount=entry.evidence().has("rawKgCO2e")
                    ?new BigDecimal(entry.evidence().path("rawKgCO2e").asText()):item.emissionKgCo2e();
            total=total.add(amount);
            var evidence=entry.evidence();var upstream=evidence.path("upstream");var slice=upstream.path("sourceSlice");
            var period=new PeriodKey(evidence.path("activity").path("startInclusive").asText(),item.scopeType().name());
            periods.merge(period,amount,BigDecimal::add);
            if("ENERGY_BILL_CARBON_V1".equals(upstream.path("contractVersion").asText())
                    &&new BigDecimal(upstream.path("excludedQuantity").asText("0")).signum()>0) {
                var record=CarbonEvidence.object();record.put("energyItemCode",item.energyItemCode());
                record.put("startInclusive",period.start());record.set("source",upstream.path("billSource"));
                record.set("grossQuantity",upstream.path("grossQuantity"));record.set("excludedQuantity",upstream.path("excludedQuantity"));
                excluded.putIfAbsent("BILL/"+upstream.path("sourceId").asText()+"/"+period.start(),record);
            }
            for(JsonNode source:upstream.path("excludedSources")) {
                String key=upstream.path("projectVersion").path("versionId").asText()+"/"+item.energyItemCode()+"/"+period.start()+"/"+source.path("pointId").asText();
                var record=CarbonEvidence.object();record.put("energyItemCode",item.energyItemCode());record.put("startInclusive",period.start());record.set("source",source);
                record.set("exclusionRules",upstream.path("projectVersion").path("configuration").path("exclusions"));excluded.putIfAbsent(key,record);
            }
            if("EXCLUDED".equals(upstream.path("activityStatus").asText()))continue;
            if(slice.isMissingNode()) {
                complete=false;
                if("ENERGY_BILL_CARBON_V1".equals(upstream.path("contractVersion").asText())) {
                    // 独立账单没有分类树；明确保留未分配总量，不伪造楼层、用途或分摊比例。
                    accumulate(nodes,new Key("REPORTING_TOTAL","","UNALLOCATED",item.energyItemCode(),item.activityUnitCode()),
                            "未分配（全楼账单）",true,item,amount);
                    warnings.add("账单仅提供总量，分项尚未覆盖，不按比例分摊");
                } else warnings.add("历史批次缺少冻结分类证据，不能按当前绑定补算");
                continue;
            }
            var configuration=upstream.path("classificationConfiguration");
            String classificationVersionId=upstream.path("projectVersion").path("configuration").path("classificationVersionId").asText("");
            for(JsonNode scheme:configuration.path("schemes")) {
                String schemeCode=scheme.path("code").asText();boolean additive="ADDITIVE".equals(scheme.path("mode").asText());
                Set<String> targets=new LinkedHashSet<>();slice.path("classifications").path(schemeCode).forEach(n->targets.add(n.asText()));
                if(targets.isEmpty()||(additive&&targets.size()!=1)) {
                    complete=false;
                    accumulate(nodes,new Key(schemeCode,classificationVersionId,"UNALLOCATED",item.energyItemCode(),item.activityUnitCode()),"未分配",additive,item,amount);
                    warnings.add("存在未分配或互斥分类歧义，未按比例分摊");continue;
                }
                Map<String,JsonNode> index=new HashMap<>();configuration.path("nodes").forEach(n->{if(schemeCode.equals(n.path("schemeCode").asText()))index.put(n.path("code").asText(),n);});
                Set<String> expanded=new LinkedHashSet<>();
                for(String target:targets) {
                    String cursor=target;Set<String> seen=new HashSet<>();
                    while(!cursor.isBlank()) {
                        if(!seen.add(cursor)||!index.containsKey(cursor))throw error(409,DEPENDENCY_UNAVAILABLE,"冻结分类树无效");
                        expanded.add(cursor);cursor=index.get(cursor).path("parentCode").asText("");
                    }
                }
                for(String node:expanded)accumulate(nodes,new Key(schemeCode,classificationVersionId,node,item.energyItemCode(),item.activityUnitCode()),index.get(node).path("name").asText(),additive,item,amount);
                if(!additive)warnings.add("交叉分类节点不可相加，父节点按来源去重");
            }
        }
        List<Node> values=nodes.entrySet().stream().map(e->new Node(e.getKey().scheme(),e.getKey().version(),e.getKey().node(),e.getValue().name,e.getKey().energy(),e.getKey().unit(),e.getValue().additive,e.getValue().quantity,e.getValue().emission)).toList();
        List<Period> trend=periods.entrySet().stream().map(e->new Period(e.getKey().start(),e.getKey().scope(),e.getValue())).toList();
        return new Report(building,batch,status,"KG_CO2E",total,complete,values,trend,List.copyOf(excluded.values()),List.copyOf(warnings),CarbonClassificationFlow.build(entries));
    }
    private static void accumulate(Map<Key,Accumulator> values,Key key,String name,boolean additive,StoredCalculationItem item,BigDecimal emission) {
        Accumulator a=values.computeIfAbsent(key,k->new Accumulator());a.name=name;a.additive=additive;
        a.quantity=a.quantity.add(item.activityQuantity());a.emission=a.emission.add(emission);
    }
}
