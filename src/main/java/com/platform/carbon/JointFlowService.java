package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.carbon.JointFlowConfigurationContracts.Version;
import com.platform.carbon.JointFlowConfigurationContracts.Dimension;
import com.platform.energy.classification.ClassificationCore;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.conversion.api.EnergyConversionContracts.ParameterVersionView;
import com.platform.energy.conversion.api.EnergyConversionContracts.SimulationRequest;
import com.platform.energy.period.EnergyPeriodAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonErrors.*;
import static com.platform.carbon.CarbonModels.*;
import static com.platform.carbon.JointFlowContracts.*;

/** 冻结叶项沿已审核展示维度组图；预览与正式查询共用计算链，不改核算及历史分类。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, timeout=60)
public class JointFlowService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final EnergyPeriodAuthorization authorization;
    private final CarbonCalculationRepository repository;
    private final JointFlowConfigurationService configurations;
    private final EnergyConversionService conversion;
    private final ObjectMapper mapper;
    private final ClassificationCore classificationCore;
    private record Window(List<YearMonth> months, Instant from, Instant to) { }
    private record Atom(StoredCalculationItem item, JsonNode evidence, List<JointFlowGraph.Target> targets, BigDecimal carbon) { }
    private record Projection(JsonNode configuration, java.util.function.BiFunction<String,Set<String>,Set<String>> assignment) { }
    private record Energy(String code, String unit) { }
    private record ActivityKey(String point, String energy) { }

    public View query(long user, Collection<String> roles, Request request) { return execute(user,roles,request,false); }
    public View preview(long user, Collection<String> roles, Request request) { return execute(user,roles,request,true); }
    private View execute(long user, Collection<String> roles, Request request, boolean preview) {
        authorization.requireReader(roles);
        if(request==null||blank(request.buildingId())||request.buildingId().length()>32)throw invalid("建筑必填");
        authorization.checkBuilding(user,roles,request.buildingId());
        Window window=window(request);
        Version display=configurations.resolve(user,roles,request.buildingId(),request.configurationVersionId(),preview);
        if(!request.buildingId().equals(display.buildingId()))throw error(409,VERSION_CONFLICT,"展示配置不属于所选建筑");
        var configuration=display.configuration();
        if(window.from().isBefore(configuration.startInclusive())||window.to().isAfter(configuration.endExclusive()))
            throw error(409,VERSION_CONFLICT,"请求期间不在展示配置适用范围");
        Map<String,Projection> projections=new HashMap<>();
        for(var mapping:configuration.mappings())projections.put(mapping.relationVersionId(),new Projection(mapper.valueToTree(mapping),classificationCore.frozenPointAssignment(mapping)));
        String mappingHash=display.configurationHash();
        List<Source> sources = new ArrayList<>();
        List<String> missing = new ArrayList<>(), warnings = new ArrayList<>();
        List<String> conversionHashes = new ArrayList<>();
        List<Atom> atoms = new ArrayList<>();
        Map<YearMonth,List<Atom>> monthly = new LinkedHashMap<>();
        for (YearMonth month : window.months()) {
            Instant from=month.atDay(1).atStartOfDay(ZONE).toInstant(), to=month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
            var candidates=repository.findJointFlowMonth(request.buildingId(), from, to);
            if (candidates.size()>1) throw error(409, VERSION_CONFLICT, "同月存在多个有效核算批次，不能自动选取");
            if (candidates.isEmpty()) { missing.add(month.toString()); continue; }
            if (atoms.size()+candidates.getFirst().detailCount()>5000) throw error(409,LIMIT_EXCEEDED,"联合图超过5000条组成，请缩小期间");
            var detail=repository.detail(candidates.getFirst().batchId());
            if (detail!=null && !candidates.getFirst().batchId().equals(detail.batch().batchId())) throw error(409,VERSION_CONFLICT,"核算详情不属于所选批次");
            validateBatch(detail, request.buildingId(), from, to);
            if (atoms.size()+detail.items().size()>5000) throw error(409, LIMIT_EXCEEDED, "联合图期间超过5000条组成，请缩小范围");
            var entries=load(detail, display, projections);
            var provenance=entries.stream().map(a->a.evidence().path("upstream")).toList();
            if(provenance.stream().anyMatch(p->blank(originalVersion(p)))||provenance.stream().map(JointFlowService::originalVersion).distinct().count()!=1
                    ||provenance.stream().map(p->p.path("classificationConfiguration").path("relationVersionId").asText()).distinct().count()!=1)
                throw error(409,VERSION_CONFLICT,"同月来源分类或计量版本不唯一，不能省略追溯");
            monthly.put(month, entries); atoms.addAll(entries);
            var published=detail.summaries().stream().filter(s->"TOTAL_EMISSION".equals(s.metricCode()) && "TOTAL".equals(s.dimensionCode())
                    && "tCO2e".equals(s.unitCode()) && s.unavailableReason()==null && s.finalValue()!=null).toList();
            if (published.size()!=1) throw error(409, DEPENDENCY_UNAVAILABLE, "发布总量证据缺失或重复");
            var first=entries.getFirst().evidence().path("upstream");
            sources.add(new Source(month.toString(),detail.batch().batchId(), originalVersion(first),
                    first.path("classificationConfiguration").path("relationVersionId").asText(),detail.batch().roundingPolicyVersionId(),
                    entries.stream().map(Atom::carbon).reduce(BigDecimal.ZERO,BigDecimal::add), published.getFirst().finalValue(),
                    sourceNature(entries), detail.items().stream().map(StoredCalculationItem::evidenceHash).sorted().toList()));
        }
        String basis=preview?"DRAFT_PREVIEW":"CONFIGURED_VIEW";
        List<ParameterVersionView> parameters=conversion.listParameterVersions(roles);
        if (parameters.size()>500) throw error(409, LIMIT_EXCEEDED,"折标参数超过查询上限");
        var approved=parameters.stream().filter(p->"APPROVED".equals(p.status()) && "PRODUCTION".equals(p.usageScope())).toList();
        var options=approved.stream().filter(p->atoms.stream().anyMatch(a->a.item().energyItemCode().equals(p.energyItemCode())))
                .map(p->new Option(p.energyItemCode(),p.versionId(),p.method(),p.perspective(),p.consumptionScope(),p.regionCode(),
                        p.effectiveFrom().toString(),p.effectiveTo()==null?null:p.effectiveTo().toString())).toList();
        if (!missing.isEmpty()) return view(request,window,display,atoms,basis,mappingHash,"INCOMPLETE",missing,sources,List.of(),List.of(),List.of(),options,
                List.of("缺少请求期间的完整月结果，不把部分月份小计当作建筑总量"),
                JointFlowGraph.unavailable("INCOMPLETE","缺少完整月结果","tCO2e"),JointFlowGraph.unavailable("INCOMPLETE","缺少完整月结果","tce"),List.of());
        JointFlowGraph carbon=new JointFlowGraph("tCO2e"), energy=new JointFlowGraph("tce");
        atoms.forEach(a->carbon.add(a.targets(),a.carbon().movePointLeft(3)));
        Set<String> parameterIds=new TreeSet<>(), formulaIds=new TreeSet<>(), coalIds=new TreeSet<>();
        List<Conversion> selections=configuration.conversions();
        if (selections.size()>64 || selections.stream().anyMatch(Objects::isNull)
                || selections.stream().map(Conversion::parameterVersionId).distinct().count()!=selections.size()) throw invalid("折标选择重复或超过64项");
        // 同一展示配置可跨月份包含多种能源；本期没有活动的能源选择不参与计算，不能迫使前端逐月改配置。
        if (selections.stream().anyMatch(s->blank(s.parameterVersionId()))) throw invalid("折标选择缺少明确版本");
        boolean selected=true;
        Set<String> perspectives=new HashSet<>();
        // 按月及能源有界批量复用正式算法；不以月总量按用途比例拆分，也不把缺失叶项tce当零。
        for (var month : monthly.entrySet()) {
            LocalDateTime from=month.getKey().atDay(1).atStartOfDay(), to=month.getKey().plusMonths(1).atDay(1).atStartOfDay();
            Map<Energy,List<Atom>> groups=new LinkedHashMap<>();
            month.getValue().forEach(a->groups.computeIfAbsent(new Energy(a.item().energyItemCode(),unit(a.item().activityUnitCode())),k->new ArrayList<>()).add(a));
            for (var group : groups.entrySet()) {
                var key=group.getKey();
                var choices=selections.stream().filter(s->key.code().equals(s.energyItemCode()) && key.unit().equals(unit(s.inputUnitCode())))
                        .map(s->approved.stream().filter(p->p.versionId().equals(s.parameterVersionId()) && p.energyItemCode().equals(key.code())).findFirst()
                                .orElseThrow(()->error(409, STATUS_CONFLICT,"所选折标参数不可用")))
                        .filter(p->!p.effectiveFrom().isAfter(from) && (p.effectiveTo()==null || !p.effectiveTo().isBefore(to))).toList();
                if (choices.size()>1) throw error(409, VERSION_CONFLICT,"同期间折标选择不唯一");
                if (choices.isEmpty()) { selected=false; continue; }
                var p=choices.getFirst(); perspectives.add(p.perspective());
                var results=conversion.evaluateFormalRulesBatch(user,roles,new SimulationRequest(request.buildingId(),key.code(),BigDecimal.ZERO,
                        key.unit(),p.method(),p.perspective(),p.consumptionScope(),p.regionCode(),from),to,
                        group.getValue().stream().map(a->a.item().activityQuantity()).toList());
                if (results.size()!=group.getValue().size()) throw error(409, DEPENDENCY_UNAVAILABLE,"折标结果条数不一致");
                // 结果指纹同时覆盖正式折标证据中的单位版本，不能只有参数ID相同就视为同一结果。
                conversionHashes.add(digest(mapper.valueToTree(results.stream().map(EnergyConversionService.FormalConversion::evidence).toList())));
                for (int i=0;i<results.size();i++) {
                    var result=results.get(i); var atom=group.getValue().get(i);
                    if (!p.versionId().equals(result.parameterVersionId()) || !"TCE".equals(result.unit())) throw error(409, VERSION_CONFLICT,"折标返回版本或单位不一致");
                    energy.add(atom.targets(),result.tce());
                    parameterIds.add(result.parameterVersionId()); formulaIds.add(result.formulaVersionId());
                    if (result.standardCoalVersionId()!=null) coalIds.add(result.standardCoalVersionId());
                }
            }
        }
        if (perspectives.size()>1) throw error(409, VERSION_CONFLICT,"不同折标视角不能合并");
        warnings.add("碳流使用冻结未舍入值；发布舍入总量另列，不能为凑差修改连线");
        warnings.add("TCE为按明确规则计算的只读分析结果，非冻结叶项TCE；正式核算性质不证明现场真实来源");
        warnings.add(preview?"草稿数值预览，不得作为正式大屏结果":"按明确展示配置组图，不改变旧核算或历史分类有效期");
        var energyGraph=selected?energy.view():JointFlowGraph.unavailable("SELECTION_REQUIRED","需要覆盖各能源和月份的明确折标选择","tce");
        var carbonGraph=carbon.view();
        if ("PARTIAL".equals(carbonGraph.state())) warnings.add("未分配节点仍计入守恒总量，不代表用途计量已经完整");
        return view(request,window,display,atoms,basis,mappingHash,carbonGraph.state(),missing,sources,List.copyOf(parameterIds),List.copyOf(formulaIds),List.copyOf(coalIds),options,warnings,carbonGraph,energyGraph,conversionHashes);
    }

    private List<Atom> load(CalculationDetail detail, Version display, Map<String,Projection> projections) {
        String shared=repository.sharedEvidence(detail.batch().batchId());
        JsonNode dictionary=shared==null?CarbonEvidence.object():CarbonEvidence.parse(shared);
        Map<String,CarbonCalculationRepository.StoredEvidence> evidence=new HashMap<>();
        for (var stored:repository.listEvidence(detail.batch().batchId())) if (evidence.put(stored.itemId(),stored)!=null) throw error(409,DEPENDENCY_UNAVAILABLE,"明细证据重复");
        if (evidence.size()!=detail.items().size()) throw error(409,DEPENDENCY_UNAVAILABLE,"明细证据不完整");
        Set<String> identities=new HashSet<>(); List<Atom> result=new ArrayList<>();
        for (var item:detail.items()) {
            var stored=evidence.get(item.calculationItemId());
            if (stored==null || !Objects.equals(stored.hash(),item.evidenceHash())) throw error(409,DEPENDENCY_UNAVAILABLE,"明细证据不一致");
            JsonNode frozen=CarbonEvidence.storedWithDictionary(stored.json(),stored.hash(),dictionary);
            JsonNode upstream=frozen.path("upstream"), slice=upstream.path("sourceSlice"), original=upstream.path("classificationConfiguration");
            validateActivity(item,frozen,detail.batch());
            if (!identities.add(item.snapshotId()) || item.activityQuantity()==null || item.activityQuantity().signum()<0)
                throw error(409,ACTIVITY_INCOMPLETE,"活动组成重复或包含无效数量");
            if (slice.isMissingNode() || slice.path("pointId").asText().isBlank()) throw error(409,DEPENDENCY_UNAVAILABLE,"缺少冻结测点组成，不能借用当前绑定补事实");
            if (!decimal(slice.path("quantity")).equals(item.activityQuantity().stripTrailingZeros())) throw error(409,DEPENDENCY_UNAVAILABLE,"活动量与冻结组成不一致");
            String relation=original.path("relationVersionId").asText();
            if(!display.configuration().relationVersionIds().contains(relation))throw error(409,VERSION_CONFLICT,"冻结计量关系不在展示配置适用范围");
            List<JointFlowGraph.Target> targets=new ArrayList<>();
            for(Dimension dimension:display.configuration().dimensions()) {
                String context=dimension.key();
                JointFlowGraph.Target target;
                if("ENERGY_TYPE".equals(dimension.key()))target=new JointFlowGraph.Target(context,item.energyItemCode(),item.energyItemCode(),true,100000);
                else if("FROZEN_SCHEME".equals(dimension.source())) {
                    validateFrozenScheme(original,dimension);
                    target=frozenTarget(original,slice,dimension.schemeCode(),context);
                } else {
                    Projection projection=projections.get(relation);
                    if(projection==null)throw error(409,DEPENDENCY_UNAVAILABLE,"缺少该关系版本的展示归属");
                    target=slice.path("residual").asBoolean(false)&&!"FLOOR".equals(dimension.key())?unallocated(context,"未分配（计量余量）")
                            :mappedTarget(projection,slice,dimension.schemeCode(),context);
                    // 楼层是历史空间事实。新归属只能补名称/排序，不能把已知历史楼层搬到别层。
                    if("FLOOR".equals(dimension.key())) {
                        validateFrozenScheme(original,dimension);
                        var old=frozenTarget(original,slice,dimension.schemeCode(),context);
                        if(old.allocated()&&(!target.allocated()||!old.code().equals(target.code())))throw error(409,VERSION_CONFLICT,"展示映射不能静默迁移冻结楼层");
                        if(!old.allocated())target=unallocated(context,"未分配楼层");
                    }
                }
                if("FLOOR".equals(dimension.key())&&target.allocated()) {
                    String code=target.code();
                    if(display.configuration().floorReferences().stream().noneMatch(ref->relation.equals(ref.relationVersionId())&&dimension.schemeCode().equals(ref.schemeCode())&&code.equals(ref.nodeCode())))
                        throw error(409,DEPENDENCY_UNAVAILABLE,"冻结楼层缺少经核验的历史楼层引用");
                }
                targets.add(decorate(target,dimension));
            }
            BigDecimal amount=decimal(frozen.path("rawKgCO2e"));
            if (amount.signum()<0) throw error(409,ACTIVITY_INCOMPLETE,"负排放暂不支持守恒Sankey图");
            result.add(new Atom(item,frozen,List.copyOf(targets),amount));
        }
        validateComposition(result);
        return result;
    }
    private static void validateActivity(StoredCalculationItem item,JsonNode evidence,CalculationBatch batch) {
        JsonNode activity=evidence.path("activity");
        if (!batch.batchId().equals(item.batchId()) || !batch.buildingId().equals(activity.path("buildingId").asText())
                || !item.snapshotId().equals(activity.path("snapshotId").asText()) || !item.energyItemCode().equals(activity.path("energyItemCode").asText())
                || !unit(item.activityUnitCode()).equals(unit(activity.path("unitCode").asText()))
                || !Objects.equals(item.factorVersionId(),evidence.path("factorVersionId").asText())
                || !Objects.equals(item.formulaVersionId(),evidence.path("formulaVersionId").asText())
                || item.activityQuantity()==null || decimal(activity.path("quantity")).compareTo(item.activityQuantity())!=0)
            throw error(409,DEPENDENCY_UNAVAILABLE,"核算行与冻结活动或规则身份不一致");
        Instant from=activityTime(activity,"startInclusive"),to=activityTime(activity,"endExclusive");
        if (!to.isAfter(from) || from.isBefore(batch.periodStart()) || to.isAfter(batch.periodEnd())) throw error(409,VERSION_CONFLICT,"冻结活动跨越所选月期间");
    }
    private static Instant activityTime(JsonNode activity,String field) {
        try { return Instant.parse(activity.path(field).asText()); }
        catch(DateTimeException exception) { throw error(409,DEPENDENCY_UNAVAILABLE,"冻结活动时间无效"); }
    }
    private static boolean overlaps(Atom left,Atom right) {
        var a=left.evidence().path("activity");var b=right.evidence().path("activity");
        return activityTime(a,"startInclusive").isBefore(activityTime(b,"endExclusive")) && activityTime(b,"startInclusive").isBefore(activityTime(a,"endExclusive"));
    }
    private static void validateComposition(List<Atom> atoms) {
        Map<ActivityKey,List<Atom>> points=new HashMap<>();
        for(Atom atom:atoms) {
            String point=atom.evidence().path("upstream").path("sourceSlice").path("pointId").asText();
            var key=new ActivityKey(point,atom.item().energyItemCode());
            points.computeIfAbsent(key,k->new ArrayList<>()).add(atom);
        }
        for(var entries:points.values()) {
            entries.sort(Comparator.comparing(a->activityTime(a.evidence().path("activity"),"startInclusive")));
            for(int i=1;i<entries.size();i++) if(overlaps(entries.get(i-1),entries.get(i)))
                throw error(409,ACTIVITY_INCOMPLETE,"同测点活动期间重叠，不能重复计量");
        }
        // 冻结组成仍复核父子互斥；计量余量可以与子表并存，整只父表不能再与子表相加。
        for(Atom atom:atoms) {
            var slice=atom.evidence().path("upstream").path("sourceSlice");
            if(slice.path("ancestors").size()>64) throw error(409,LIMIT_EXCEEDED,"计量祖先超过64层");
            String own=slice.path("pointId").asText();
            for(JsonNode ancestor:slice.path("ancestors")) {
                if(own.equals(ancestor.asText()))continue;
                var parents=points.getOrDefault(new ActivityKey(ancestor.asText(),atom.item().energyItemCode()),List.of());
                if(parents.stream().anyMatch(parent->!parent.evidence().path("upstream").path("sourceSlice").path("residual").asBoolean(false) && overlaps(atom,parent)))
                    throw error(409,ACTIVITY_INCOMPLETE,"冻结组成同时包含总表与分表，不能重复计量");
            }
        }
    }
    private static JointFlowGraph.Target frozenTarget(JsonNode config,JsonNode slice,String scheme,String context) {
        var targets=slice.path("classifications").path(scheme);
        if(targets.isArray()&&targets.size()>1)throw error(409,VERSION_CONFLICT,"冻结叶项存在重复归属");
        return targets.isArray() && targets.size()==1?target(config,scheme,targets.get(0).asText(),context):unallocated(context,"未分配");
    }
    private JointFlowGraph.Target mappedTarget(Projection projection,JsonNode slice,String scheme,String context) {
        Set<String> ancestors=new TreeSet<>();slice.path("ancestors").forEach(a->ancestors.add(a.asText()));
        ancestors.add(slice.path("pointId").asText());
        var nodes=projection.assignment().apply(scheme,ancestors).stream().toList();
        if(nodes.size()>1)throw error(409,VERSION_CONFLICT,"冻结叶项存在重复归属，不能重复计量");
        return nodes.size()==1?target(projection.configuration(),scheme,nodes.getFirst(),context):unallocated(context,"未分配");
    }
    private static JointFlowGraph.Target decorate(JointFlowGraph.Target target,Dimension dimension) {
        return dimension.nodes().stream().filter(n->n.code().equals(target.code())).findFirst()
                .map(n->new JointFlowGraph.Target(target.context(),target.code(),n.name(),target.allocated(),n.order())).orElse(target);
    }
    private static JointFlowGraph.Target target(JsonNode config,String scheme,String code,String context) {
        List<JsonNode> nodes=new ArrayList<>(); config.path("nodes").forEach(n->{if (scheme.equals(n.path("schemeCode").asText()) && code.equals(n.path("code").asText())) nodes.add(n);});
        if (nodes.size()!=1) throw error(409,DEPENDENCY_UNAVAILABLE,"分类节点缺失或重复");
        return new JointFlowGraph.Target(context,code,nodes.getFirst().path("name").asText(),true,100000);
    }
    private static JointFlowGraph.Target unallocated(String context,String name) { return new JointFlowGraph.Target(context,"UNALLOCATED",name,false,100001); }
    private void validateFrozenScheme(JsonNode config,Dimension dimension) {
        try {
            var configuration=mapper.treeToValue(config,com.platform.energy.classification.ClassificationContracts.Configuration.class);
            JointFlowConfigurationService.validateScheme(configuration,dimension);
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw error(409,DEPENDENCY_UNAVAILABLE,"冻结分类结构损坏"); }
    }
    private static void validateBatch(CalculationDetail detail,String building,Instant from,Instant to) {
        if (detail==null || detail.items().isEmpty() || !detail.failures().isEmpty()) throw error(409,DEPENDENCY_UNAVAILABLE,"核算明细不完整");
        var b=detail.batch();
        if (!building.equals(b.buildingId()) || !from.equals(b.periodStart()) || !to.equals(b.periodEnd()) || !ZONE.getId().equals(b.timezoneId())
                || b.periodType()!=PeriodType.MONTH || b.resultNature()!=ResultNature.FORMAL || !Set.of("DIRECT","PUBLISHED").contains(b.publicationStatus())
                || !"COMPLETED_COMPLETE".equals(b.status()) || b.detailCount()!=detail.items().size()) throw error(409,VERSION_CONFLICT,"核算批次范围或发布状态不一致");
    }
    private static Window window(Request request) {
        try {
            List<YearMonth> months=new ArrayList<>();
            if ("MONTH".equals(request.periodType())) {
                YearMonth month=YearMonth.parse(request.period());
                if (!blank(request.throughMonth())) throw invalid("月查询不能携带年度截止月");
                months.add(month);
            } else if ("YEAR".equals(request.periodType())) {
                if (!request.period().matches("\\d{4}") || blank(request.throughMonth())) throw invalid("年查询必须明确截止月");
                int year=Integer.parseInt(request.period()); YearMonth end=YearMonth.parse(request.throughMonth());
                if (year!=end.getYear()) throw invalid("截止月必须属于所选年度");
                for(int month=1;month<=end.getMonthValue();month++)months.add(YearMonth.of(year,month));
            } else throw invalid("只支持年或月");
            if (months.getFirst().getYear()<2000 || months.getLast().getYear()>2200) throw invalid("期间超出2000至2200年");
            return new Window(months,months.getFirst().atDay(1).atStartOfDay(ZONE).toInstant(),months.getLast().plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant());
        } catch (DateTimeException|NumberFormatException|NullPointerException exception) { throw invalid("期间无效"); }
    }
    private View view(Request request,Window window,Version display,List<Atom> atoms,String basis,String mappingHash,String state,List<String> missing,List<Source> sources,
                      List<String> parameters,List<String> formulas,List<String> coal,List<Option> options,List<String> warnings,Graph carbon,Graph energy,List<String> conversionHashes) {
        String fingerprint=digest(mapper.valueToTree(List.of(request,display.versionId(),sources,parameters,formulas,coal,basis,mappingHash,conversionHashes)));
        return new View(request.buildingId(),request.periodType(),request.period(),request.throughMonth(),ZONE.getId(),window.from(),window.to(),state,basis,
                display.versionId(),mappingHash,fingerprint,List.copyOf(missing),List.copyOf(sources),parameters,formulas,coal,options,List.copyOf(warnings),carbon,energy,display.configuration().dimensions(),coverage(display.configuration().dimensions(),atoms));
    }
    private static String sourceNature(List<Atom> atoms) {
        // 正式封账不是来源性质。仅传播明确测试标记；没有标记仍为未确认，绝不自动升级为真实。
        Set<JsonNode> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for (Atom atom : atoms) {
            JsonNode upstream=atom.evidence().path("upstream");
            for (String field : List.of("compositionSnapshots","sources")) {
                JsonNode references=upstream.path(field);
                if (seen.add(references) && references.toString().contains("TEST_ONLY")) return "DECLARED_TEST_SOURCE";
            }
        }
        return "UNDETERMINED";
    }
    private static Coverage coverage(List<Dimension> dimensions,List<Atom> atoms) {
        List<DimensionCoverage> coverage=new ArrayList<>();
        for(int i=0;i<dimensions.size();i++) {
            final int index=i;
            int assigned=(int)atoms.stream().filter(a->a.targets().get(index).allocated()).count();
            coverage.add(new DimensionCoverage(dimensions.get(i).key(),assigned,atoms.size()-assigned));
        }
        return new Coverage(atoms.size(),(int)atoms.stream().filter(a->a.targets().stream().allMatch(JointFlowGraph.Target::allocated)).count(),List.copyOf(coverage));
    }
    private static String originalVersion(JsonNode upstream) { return upstream.path("projectVersion").path("configuration").path("classificationVersionId").asText(); }
    private static String digest(JsonNode node) { return CarbonCalculationCore.sha256(CarbonEvidence.canonical(node)); }
    private static BigDecimal decimal(JsonNode value) {
        try { if (!value.isValueNode() || value.isNull()) throw new NumberFormatException(); return new BigDecimal(value.asText()).stripTrailingZeros(); }
        catch(NumberFormatException exception) { throw error(409,DEPENDENCY_UNAVAILABLE,"冻结数值证据缺失或无效"); }
    }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static String unit(String value) { return value==null?"":value.trim().toUpperCase(Locale.ROOT); }
    private static com.platform.framework.exception.BusinessException invalid(String message) { return error(400,VALIDATION_FAILED,message); }
}
