package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.energy.activity.EnergyActivityDataContracts.AggregationActivitySnapshot;
import com.platform.energy.activity.EnergyActivityDataService;
import com.platform.energy.aggregation.EnergyAggregationModels.ActivityFact;
import com.platform.energy.aggregation.NativeQuantityAggregationService;
import com.platform.energy.aggregation.NativeQuantityAggregationService.*;
import com.platform.energy.catalog.EnergyCatalogLookup;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView;
import com.platform.energy.classification.ClassificationContracts.ReadingView;
import com.platform.energy.classification.ClassificationCore;
import com.platform.energy.classification.ClassificationService;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.platform.carbon.CarbonErrors.*;

/** 通过公开来源/质量/历史关系端口取小时原生量；不扫描周期封账或按月量拆小时。 */
@Service
@RequiredArgsConstructor
public class HourlyCarbonInputService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final CarbonProjectService projects;
    private final CarbonProjectCore projectCore;
    private final ClassificationService classifications;
    private final ClassificationCore classificationCore;
    private final EnergyActivityDataService activities;
    private final NativeQuantityAggregationService nativeQuantities;
    private final EnergyCatalogService catalog;
    private final EnergyCatalogLookup units;

    public record Activity(String energyItemCode,BigDecimal quantityKwh,JsonNode evidence) { }
    public record Input(List<Activity> activities,List<String> sourceSnapshotIds,List<String> qualityPolicyVersions,
                        List<String> projectVersionIds,List<String> relationVersionIds,JsonNode evidence) { }
    private record Raw( AggregationActivitySnapshot snapshot,GovernedEvidence governed) { }

    /** 缓存只活在一次命令中；同一测点整日事实只取一次，规则切换仍逐小时核验。 */
    public static final class Capture {
        final boolean reviewedDayCache;
        final boolean monitoringBoundaries;
        final Map<String,Raw> raw=new HashMap<>();
        final Map<String,BindingVersionView> bindings=new HashMap<>();
        final Map<String,EnergyCatalogLookup.ApprovedEnergyItem> items=new HashMap<>();
        final Map<String,EnergyCatalogLookup.UnitConversion> units=new HashMap<>();
        final Map<String,JsonNode> contexts=new TreeMap<>();
        final Set<String> monitoringContexts=new HashSet<>();
        int factCount;
        private ReviewedScope reviewedScope;
        private List<ReadingView> reviewedReadings;
        public Capture(){this(false);}
        Capture(boolean reviewedDayCache){this(reviewedDayCache,false);}
        Capture(boolean reviewedDayCache,boolean monitoringBoundaries){this.reviewedDayCache=reviewedDayCache;this.monitoringBoundaries=monitoringBoundaries;}
        public Map<String,JsonNode> contexts() { return Map.copyOf(contexts); }
    }
    private record ReviewedScope(long user,Set<String> roles,String building,Instant asOf,Instant from,Instant to) { }

    public Input read(long user,Collection<String> roles,HourlyCarbonContracts.Request request,
                      Instant from,Instant to,Instant readFrom,Instant readTo,Capture capture,CarbonCalculationDeadline deadline) {
        deadline.requireRemaining();
        if(capture.reviewedDayCache && "REVIEWED_INTERVAL".equals(request.sourceKind())) {
            var scope=new ReviewedScope(user,Set.copyOf(roles),request.buildingId(),request.calculationAsOf(),readFrom,readTo);
            if(capture.reviewedScope!=null&&!capture.reviewedScope.equals(scope)
                    ||from.isBefore(readFrom)||to.isAfter(readTo))
                throw error(409,ACTIVITY_INCOMPLETE,"审核日来源捕获不能跨用户、角色、建筑、水位或日期复用");
            capture.reviewedScope=scope;
        }
        var project=projects.effective(request.buildingId(),from,to);
        if(project==null)throw error(409,DEPENDENCY_UNAVAILABLE,"该小时没有生效碳项目配置");
        var codes=project.configuration().energies().stream().filter(e->"ENABLED".equals(e.capability())&&"PURCHASED_ELECTRICITY".equals(e.pathway()))
                .map(CarbonProjectContracts.Energy::energyItemCode).distinct().toList();
        if(codes.isEmpty()||codes.size()>16)throw error(409,DEPENDENCY_UNAVAILABLE,"外购电力未启用或超过16种能源");
        var manual="REVIEWED_INTERVAL".equals(request.sourceKind())
                ? reviewedReadings(user,roles,request,from,to,readFrom,readTo,capture,deadline):List.<ReadingView>of();
        List<Activity> results=new ArrayList<>();
        Set<String> sourceIds=new TreeSet<>(),qualityIds=new TreeSet<>(),relations=new TreeSet<>();
        var trace=CarbonEvidence.object();
        var energyTrace=trace.putArray("energies");
        Set<String> hourPoints=new HashSet<>();
        for(String code:codes) {
            var context=context(user,roles,request.buildingId(),project.configuration().classificationVersionId(),from,to,code,capture.monitoringBoundaries);
            for(var meter:context.meters())if(!hourPoints.add(meter.pointId()))
                throw error(409,ACTIVITY_INCOMPLETE,"同一测点被多个外购电力来源重复引用");
            if(hourPoints.size()>500)throw error(409,LIMIT_EXCEEDED,"小时来源超过500个测点");
            var contextTree=CarbonEvidence.object();contextTree.set("project",CarbonEvidence.tree(project));contextTree.set("metering",CarbonEvidence.tree(context));
            String contextHash=hash(contextTree);capture.contexts.putIfAbsent(contextHash,contextTree);
            relations.add(context.relationVersionId());
            List<ClassificationCore.Fact> facts=new ArrayList<>();
            var pointTrace=CarbonEvidence.object();
            Set<String> missing=new TreeSet<>();
            // 分类树仅投影互斥贡献，所有原生事实先按审核单位固定比例转换到kWh，不能跨能源混加。
            for(var rule:context.configuration().boundaryRules())conversion(BigDecimal.ONE,rule.unit(),from,to,capture);
            for(var meter:context.meters()) {
                deadline.requireRemaining();
                try {
                    var binding=binding(user,roles,request.buildingId(),meter.pointId(),from,to,capture);
                    if(!code.equals(binding.energyItemCode()))throw error(409,ACTIVITY_INCOMPLETE,"测点有效能源绑定与外购电力口径不一致");
                    var item=capture.items.get(code);
                    if(item==null||!covers(item.effectiveFrom(),item.effectiveTo(),from,to)) {
                        item=units.findApprovedItem(code,local(from)).orElseThrow(()->error(409,DEPENDENCY_UNAVAILABLE,"外购电力目录尚未审核"));
                        var lastItem=units.findApprovedItem(code,local(to.minusNanos(1))).orElseThrow(()->error(409,DEPENDENCY_UNAVAILABLE,"外购电力目录有效期不足"));
                        if(!item.versionId().equals(lastItem.versionId()))throw error(409,DEPENDENCY_UNAVAILABLE,"小时内外购电力目录换版");
                        capture.items.put(code,item);
                    }
                    if(!"ELECTRICITY".equals(item.compatibleCategory()) || !item.usageScopes().contains("PURCHASED_ELECTRICITY")
                            || !item.versionId().equals(binding.energyItemVersionId()))
                        throw error(409,DEPENDENCY_UNAVAILABLE,"外购电力目录或绑定版本未覆盖完整小时");
                    var point=CarbonEvidence.object();BigDecimal quantity;
                    if("RAW_CUMULATIVE".equals(request.sourceKind())) {
                        Raw raw=capture.raw.get(meter.pointId());
                        if(raw==null) {
                            var snapshot=activities.aggregationSnapshot(user,roles,request.buildingId(),meter.pointId(),readFrom.toEpochMilli(),readTo.toEpochMilli(),request.calculationAsOf().toEpochMilli());
                            if(!request.buildingId().equals(snapshot.buildingId()) || !meter.pointId().equals(snapshot.pointId())
                                    || snapshot.activityWatermark()!=request.calculationAsOf().toEpochMilli()
                                    || snapshot.calculationAsOf()!=request.calculationAsOf().toEpochMilli()
                                    || snapshot.startInclusive()!=readFrom.toEpochMilli() || snapshot.endExclusive()!=readTo.toEpochMilli())
                                throw error(409,ACTIVITY_INCOMPLETE,"原始活动快照身份或接收截止不一致");
                            capture.factCount+=snapshot.items().size();
                            if(capture.factCount>100_000)throw error(409,LIMIT_EXCEEDED,"单日活动事实超过100000条");
                            var governed=nativeQuantities.governedEvidence(user,roles,request.buildingId(),meter.pointId(),readFrom,readTo,request.calculationAsOf());
                            raw=new Raw(snapshot,governed);capture.raw.put(meter.pointId(),raw);
                        }
                        var snapshot=raw.snapshot();
                        if(!"CUMULATIVE".equals(snapshot.valueSemantics()) || !"CONFIRMED".equals(snapshot.confirmationStatus())
                                || !binding.rawUnit().equalsIgnoreCase(snapshot.sourceUnit()))throw error(409,ACTIVITY_INCOMPLETE,"小时原始来源须为已确认累计量，单位与绑定一致");
                        var approximate=capture.monitoringBoundaries?HourlyMonitoringBoundary.select(snapshot.items(),from,to,to.isBefore(from.plusSeconds(3600))):null;
                        Instant actualFrom=approximate==null?from:approximate.from(),actualTo=approximate==null?to:approximate.to();
                        var used=approximate==null?snapshot.items().stream().filter(v->v.eventTime()>=from.toEpochMilli()&&v.eventTime()<=to.toEpochMilli()).toList():approximate.items();
                        if(approximate!=null) {
                            if(!covers(item.effectiveFrom(),item.effectiveTo(),actualFrom,actualTo))
                                throw error(409,DEPENDENCY_UNAVAILABLE,"邻近读数跨越能源目录有效边界");
                            // 邻近读数不能跨已生效的项目、关系或绑定边界偷用旧表；冲突仍交由缺失逻辑处理。
                            String contextKey=code+":"+actualFrom+":"+actualTo;
                            if((!actualFrom.equals(from)||!actualTo.equals(to))&&!capture.monitoringContexts.contains(contextKey)) {
                                var actualProject=projects.effective(request.buildingId(),actualFrom,actualTo);
                                if(actualProject==null||!actualProject.versionId().equals(project.versionId()))
                                    throw error(409,ACTIVITY_INCOMPLETE,"邻近读数跨越项目有效边界");
                                var actualContext=context(user,roles,request.buildingId(),project.configuration().classificationVersionId(),actualFrom,actualTo,code,true);
                                if(!actualContext.relationVersionId().equals(context.relationVersionId()))
                                    throw error(409,ACTIVITY_INCOMPLETE,"邻近读数跨越计量关系边界");
                                capture.monitoringContexts.add(contextKey);
                            }
                            if(!binding(user,roles,request.buildingId(),meter.pointId(),actualFrom,actualTo,capture).bindingVersionId().equals(binding.bindingVersionId()))
                                throw error(409,ACTIVITY_INCOMPLETE,"邻近读数跨越计量关系或能源绑定边界");
                            point.put("actualStart",actualFrom.toString());point.put("actualEnd",actualTo.toString());
                            point.put("boundaryPolicy","PRECEDING_SIX_MINUTES_V1");
                        }
                        var sourceFacts=used.stream().map(v->new ActivityFact(v.pointId()+"@"+v.eventTime(),BigDecimal.valueOf(v.rawValue()),Instant.ofEpochMilli(v.eventTime()),
                                Instant.ofEpochMilli(v.receivedTime()),v.qualityLevel(),v.policySource()+":"+v.policyVersion()+":"+v.policyConfigRevision(),v.late(),null,null,null,null)).toList();
                        var factIds=sourceFacts.stream().map(ActivityFact::factIdentity).collect(java.util.stream.Collectors.toSet());
                        var governed=new GovernedEvidence(raw.governed().meterEvents().stream().filter(e->e.occurredAt().isAfter(actualFrom)&&!e.occurredAt().isAfter(actualTo)).toList(),
                                raw.governed().corrections().stream().filter(c->factIds.contains(c.originalFactIdentity())).toList());
                        var result=nativeQuantities.aggregateCumulative(new NativeCumulativeRequest(new NativeScope(request.buildingId(),meter.pointId(),actualFrom,actualTo,
                                request.calculationAsOf(),request.calculationAsOf()),snapshot.sourceUnit(),sourceFacts,governed.meterEvents(),governed.corrections()));
                        var converted=conversion(result.quantity(),snapshot.sourceUnit(),actualFrom,actualTo,capture);quantity=converted.convertedQuantity();
                        conversionEvidence(point,converted,capture);
                        used.forEach(v->qualityIds.add(v.policySource()+":"+v.policyVersion()+":"+v.policyConfigRevision()));
                        point.set("facts",CarbonEvidence.tree(used));point.set("governed",CarbonEvidence.tree(governed));
                        point.set("nativeResult",CarbonEvidence.tree(result));point.put("profileRevision",snapshot.profileRevision());
                    } else {
                        var matches=manual.stream().filter(v->meter.pointId().equals(v.reading().pointId())).toList();
                        if(matches.size()!=1)throw error(409,ACTIVITY_INCOMPLETE,"该小时须有唯一已审核精确区间来源");
                        var stored=matches.getFirst();var value=stored.reading();
                        if(!from.equals(value.startInclusive())||!to.equals(value.endExclusive())||!"EXACT".equals(value.timePrecision())
                                ||!"CONFIRMED".equals(value.status())||!code.equals(value.energyItemCode())||!binding.rawUnit().equalsIgnoreCase(value.unit()))
                            throw error(409,ACTIVITY_INCOMPLETE,"人工来源边界、单位或能源不匹配该小时，不能比例拆分");
                        var converted=conversion(stored.normalizedQuantity(),value.unit(),from,to,capture);quantity=converted.convertedQuantity();
                        conversionEvidence(point,converted,capture);
                        point.set("reviewedReading",CarbonEvidence.tree(stored));
                    }
                    var bindingTree=CarbonEvidence.tree(binding);String bindingHash=hash(bindingTree);
                    capture.contexts.putIfAbsent(bindingHash,bindingTree);point.put("bindingReference",bindingHash);
                    point.put("quantityKwh",quantity.toPlainString());
                    point.put("energyItemVersionId",item.versionId());
                    String pointHash=hash(point);sourceIds.add(pointHash);pointTrace.set(meter.pointId(),point);
                    facts.add(new ClassificationCore.Fact(meter.pointId(),quantity,pointHash,"MONITORING_SOURCE",true));
                } catch(BusinessException ex) {
                    if(ex.getCode()==401||ex.getCode()==403||ex.getCode()>=500||LIMIT_EXCEEDED.equals(ex.getErrorCode()))throw ex;
                    // 缺失的分表保持缺失交给同一计量树判完整性；总表口径允许不依赖无关子表。
                    missing.add(meter.pointId()+":"+Objects.toString(ex.getErrorCode(),"SOURCE_NOT_USABLE"));
                }
            }
            var computation=classificationCore.compute(context.configuration(),context.configuration().schemes().getFirst().code(),List.of(),
                    new ClassificationCore.Input(context.meters(),facts,context.resolvedBindings()));
            if(!"CALCULABLE".equals(computation.status())||computation.total()==null)
                throw error(409,ACTIVITY_INCOMPLETE,"小时计量组成不完整："+String.join(",",missing.stream().limit(5).toList()));
            if(computation.sourceSlices().stream().anyMatch(s->s.quantity().signum()<0))throw error(409,ACTIVITY_INCOMPLETE,"存在出量或负余量，不能作为外购电力入量");
            var applied=projectCore.apply(project.configuration(),code,computation.sourceSlices());
            var energy=CarbonEvidence.object();energy.put("energyItemCode",code);energy.put("contextId",contextHash);
            energy.set("pointInputs",pointTrace);energy.set("composition",CarbonEvidence.tree(computation.sourceSlices()));
            energy.set("projectApplied",CarbonEvidence.tree(applied));energy.set("missingUnrequiredSources",CarbonEvidence.tree(missing));
            energyTrace.add(energy);results.add(new Activity(code,applied.included(),energy));
        }
        return new Input(List.copyOf(results),List.copyOf(sourceIds),List.copyOf(qualityIds),List.of(project.versionId()),List.copyOf(relations),trace);
    }

    private List<ReadingView> reviewedReadings(long user,Collection<String> roles,HourlyCarbonContracts.Request request,
            Instant from,Instant to,Instant readFrom,Instant readTo,Capture capture,CarbonCalculationDeadline deadline) {
        if(capture.monitoringBoundaries&&!Duration.between(from,to).equals(Duration.ofHours(1)))return List.of();
        if(!capture.reviewedDayCache)
            return classifications.reviewedIntervalReadings(user,roles,request.buildingId(),from,to,request.calculationAsOf());
        if(capture.reviewedReadings==null)
            capture.reviewedReadings=List.copyOf(classifications.reviewedDayIntervalReadings(user,roles,request.buildingId(),
                    readFrom,readTo,request.calculationAsOf(),deadline::remaining));
        // 保留原完整重叠判断；跨日/月事实仍交给下游精确小时校验，不能裁剪或按比例分摊。
        var values=capture.reviewedReadings.stream().filter(value->value.reading().startInclusive().isBefore(to)
                && value.reading().endExclusive().isAfter(from)).toList();
        if(values.size()>5000)throw error(409,LIMIT_EXCEEDED,"该小时审核来源超过5000条有界查询上限");
        return values;
    }

    private ClassificationService.IntervalContext context(long user,Collection<String> roles,String building,String version,
            Instant from,Instant to,String energy,boolean monitoring) {
        if(!monitoring)return classifications.intervalContext(user,roles,building,version,from,to,energy);
        var split=from.plusSeconds(3600);
        var first=classifications.monitoringIntervalContext(user,roles,building,version,from,to.isAfter(split)?split:to,energy);
        if(to.isAfter(split)) {
            // 两个邻近锚点可能覆盖略多于一小时；分段核验同一配置，不放宽正式小时端口。
            var last=classifications.monitoringIntervalContext(user,roles,building,version,split,to,energy);
            if(!first.classificationVersionId().equals(last.classificationVersionId())
                    ||!first.relationVersionId().equals(last.relationVersionId())||first.modelRevision()!=last.modelRevision())
                throw error(409,ACTIVITY_INCOMPLETE,"邻近读数跨越计量配置版本");
        }
        return first;
    }

    private BindingVersionView binding(long user,Collection<String> roles,String building,String point,Instant from,Instant to,Capture capture) {
        var value=capture.bindings.get(point);
        if(value==null || value.effectiveFrom().isAfter(local(from)) || value.effectiveTo()!=null && value.effectiveTo().isBefore(local(to))) {
            value=catalog.effectiveBinding(user,roles,building,point,local(from));
            var last=catalog.effectiveBinding(user,roles,building,point,local(to.minusNanos(1)));
            if(!value.bindingVersionId().equals(last.bindingVersionId())||!"CONFIRMED".equals(value.confirmationStatus()))
                throw error(409,ACTIVITY_INCOMPLETE,"小时内测点能源绑定换版或尚未确认");
            capture.bindings.put(point,value);
        }
        return value;
    }

    private EnergyCatalogLookup.UnitConversion conversion(BigDecimal quantity,String unit,Instant from,Instant to,Capture capture) {
        String key=unit.toUpperCase(Locale.ROOT);
        var conversion=capture.units.get(key);
        if(conversion==null||!covers(conversion.inputUnit().effectiveFrom(),conversion.inputUnit().effectiveTo(),from,to)
                ||!covers(conversion.targetUnit().effectiveFrom(),conversion.targetUnit().effectiveTo(),from,to)) {
            conversion=units.convert(BigDecimal.ONE,unit,"KWH",local(from)).orElseThrow(()->error(409,UNIT_INCOMPATIBLE,"小时电量只能使用已审核的固定比例电能单位"));
            var last=units.convert(BigDecimal.ONE,unit,"KWH",local(to.minusNanos(1))).orElseThrow(()->error(409,UNIT_INCOMPATIBLE,"小时末尾电能单位不可用"));
            if(!conversion.inputUnit().versionId().equals(last.inputUnit().versionId())||!conversion.targetUnit().versionId().equals(last.targetUnit().versionId()))
                throw error(409,UNIT_INCOMPATIBLE,"小时内电能单位版本切换");
            capture.units.put(key,conversion);
        }
        // 固定单位比例和DECIMAL128运算顺序沿用目录；复用同次捕获的版本，不对每个小时重复查字典。
        var converted=quantity.multiply(conversion.inputUnit().scaleFactor(),java.math.MathContext.DECIMAL128)
                .divide(conversion.targetUnit().scaleFactor(),java.math.MathContext.DECIMAL128);
        return new EnergyCatalogLookup.UnitConversion(quantity,conversion.inputUnit(),converted,conversion.targetUnit());
    }
    private static boolean covers(LocalDateTime start,LocalDateTime end,Instant from,Instant to) {
        return start!=null&&!start.isAfter(local(from))&&(end==null||!end.isBefore(local(to)));
    }
    private static void conversionEvidence(com.fasterxml.jackson.databind.node.ObjectNode point,
            EnergyCatalogLookup.UnitConversion converted,Capture capture) {
        var metadata=CarbonEvidence.object();metadata.set("inputUnit",CarbonEvidence.tree(converted.inputUnit()));
        metadata.set("targetUnit",CarbonEvidence.tree(converted.targetUnit()));
        String key=hash(metadata);capture.contexts.putIfAbsent(key,metadata);
        var trace=point.putObject("unitConversion");trace.put("definitionReference",key);
        trace.put("inputQuantity",converted.inputQuantity().toPlainString());trace.put("convertedQuantity",converted.convertedQuantity().toPlainString());
    }
    private static LocalDateTime local(Instant at) { return LocalDateTime.ofInstant(at,ZONE); }
    private static String hash(JsonNode value) { return CarbonCalculationCore.sha256(CarbonEvidence.canonical(value)); }
}
