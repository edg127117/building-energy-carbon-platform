package com.platform.energy.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.classification.ClassificationContracts;
import com.platform.energy.classification.ClassificationCore.SourceSlice;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.conversion.api.EnergyConversionContracts.ParameterVersionView;
import com.platform.energy.conversion.api.EnergyConversionContracts.SimulationRequest;
import com.platform.energy.period.EnergyPeriodAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static com.platform.energy.summary.EnergyFlowContracts.*;
import static com.platform.energy.summary.EnergySummaryErrors.*;

/** 从既有互斥计量组成逐份折标，形成能源→用途层级→建筑的流图，不相加总表和分表。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class EnergyFlowService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final EnergyPeriodAuthorization authorization;
    private final ClassificationService classifications;
    private final EnergyConversionService conversion;
    private final ObjectMapper mapper;

    public View query(long user, Collection<String> roles, Request request) {
        authorization.requireReader(roles);
        if (request == null || request.buildingId() == null || request.buildingId().isBlank()
                || request.buildingId().length()>32) throw error(400, VALIDATION_FAILED, "建筑编码无效");
        authorization.checkBuilding(user, roles, request.buildingId());
        YearMonth month;
        try { month=YearMonth.parse(request.month()); }
        catch (RuntimeException e) { throw error(400, VALIDATION_FAILED, "月份须为YYYY-MM"); }
        if (month.getYear()<2000 || month.getYear()>2200) throw error(400, VALIDATION_FAILED, "月份超出范围");
        Instant start=month.atDay(1).atStartOfDay(ZONE).toInstant();
        Instant end=month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
        var version=classifications.effectiveVersion(user,roles,request.buildingId(),start,end,request.classificationVersionId());
        var config=version.configuration();
        if (config.schemes().isEmpty()) throw error(409, POLICY_REQUIRED, "尚无分类方案");
        var energies=config.boundaryRules().stream().map(r->new Energy(r.energyItemCode(),r.unit())).distinct().toList();
        if (energies.isEmpty() || energies.size()>16) throw error(409, POLICY_REQUIRED, "能源计量口径缺失或超过16项");
        List<Selection> selections=request.selections()==null?List.of():request.selections();
        if (selections.size()>16 || selections.stream().anyMatch(Objects::isNull)
                || selections.stream().map(s->new Energy(s.energyItemCode(),s.unit())).distinct().count()!=selections.size()
                || selections.stream().anyMatch(s->!energies.contains(new Energy(s.energyItemCode(),s.unit()))))
            throw error(400, VALIDATION_FAILED, "折标选择重复或不属于当前能源口径");
        var from=LocalDateTime.ofInstant(start,ZONE);
        var to=LocalDateTime.ofInstant(end,ZONE);
        // 参数选用是专业口径。这里只提供后端已审核选项，查看者明确选择，不猜地区、视角或最新版本。
        var parameters=conversion.listParameterVersions(roles).stream().filter(p->"APPROVED".equals(p.status())
                && "PRODUCTION".equals(p.usageScope()) && !p.effectiveFrom().isAfter(from)
                && (p.effectiveTo()==null || !p.effectiveTo().isBefore(to))).toList();
        List<Option> options=new ArrayList<>();
        for (Energy energy:energies) for(var p:parameters) if(energy.code().equals(p.energyItemCode()))
            options.add(new Option(energy.code(),energy.unit(),p.versionId(),p.parameterCode(),p.method(),p.perspective(),p.consumptionScope(),p.regionCode()));
        if(options.size()>500) throw error(409, RESULT_LIMIT_EXCEEDED, "折标选项超过500项，请收敛有效参数");
        var missing=energies.stream().filter(e->options.stream().noneMatch(o->e.code().equals(o.energyItemCode()) && e.unit().equals(o.unit())))
                .map(Energy::code).toList();
        if(!missing.isEmpty())return view(request,version,"CONFIGURATION_REQUIRED",
                "以下能源缺少覆盖整月的已审核折标参数："+String.join("、",missing),"UNDETERMINED",null,
                List.of(),List.of(),List.of(),List.of(),options,List.of());
        Map<Energy,ParameterVersionView> chosen=new LinkedHashMap<>();
        for(Selection s:selections) {
            var p=parameters.stream().filter(v->v.versionId().equals(s.parameterVersionId()) && v.energyItemCode().equals(s.energyItemCode()))
                    .findFirst().orElseThrow(()->error(409,POLICY_REQUIRED,"所选折标参数未审核或未覆盖整月"));
            chosen.put(new Energy(s.energyItemCode(),s.unit()),p);
        }
        if(chosen.size()!=energies.size()) return view(request,version,"SELECTION_REQUIRED",
                "请选择每种能源的已审核折标口径；此操作仅用于本次查看", "UNDETERMINED",null,
                List.of(),List.of(),List.of(),List.of(),options,List.of());
        if(chosen.values().stream().map(ParameterVersionView::perspective).distinct().count()!=1)
            throw error(409,POLICY_REQUIRED,"不同折标视角不能合并为建筑tce总量");
        Map<String,Builder> builders=new LinkedHashMap<>();
        config.schemes().forEach(s->builders.put(s.code(),new Builder(s.code(),"ADDITIVE".equals(s.mode()))));
        Set<String> evidence=new TreeSet<>(),parameterIds=new TreeSet<>(),formulaIds=new TreeSet<>(),coalIds=new TreeSet<>();
        List<Object> trace=new ArrayList<>();
        String nature="FORMAL_SOURCE";
        int sourceCount=0;
        for(Energy energy:energies) {
            var projection=classifications.queryProjection(user,roles,request.buildingId(),new ClassificationContracts.QueryRequest(
                    start,end,"MONTH",energy.code(),energy.unit(),config.schemes().getFirst().code(),version.versionId(),List.of()));
            var nativeResult=projection.view();
            if(!"CALCULABLE".equals(nativeResult.status()) || nativeResult.total()==null)
                return view(request,version,"INCOMPLETE","计量组成缺测或关系未确认，不能生成完整能流",nativeResult.resultNature(),null,
                        List.of(),List.of(),List.of(),nativeResult.evidenceIds(),options,List.of());
            if(!"BOUNDARY_TOTAL".equals(nativeResult.totalKind()))
                throw error(409,POLICY_REQUIRED,"分表小计不能冒充建筑边界总量");
            var slices=projection.slices();
            sourceCount+=slices.size();
            if(sourceCount>500) throw error(409,RESULT_LIMIT_EXCEEDED,"能流互斥组成超过500项");
            if(slices.stream().anyMatch(s->s.quantity().signum()<0))
                throw error(409,POLICY_REQUIRED,"存在出量或负余量，不能绘制当前入量能流");
            if(slices.stream().map(SourceSlice::quantity).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(nativeResult.total())!=0)
                throw error(409,VERSION_CONFLICT,"互斥组成与边界总量不一致");
            if(!"FORMAL_SOURCE".equals(nativeResult.resultNature())) nature="SOURCE_LABELLED_STATISTICS";
            evidence.addAll(nativeResult.evidenceIds());
            trace.add(nativeResult);
            var p=chosen.get(energy);
            Map<BigDecimal,BigDecimal> values=new HashMap<>();
            for(SourceSlice slice:slices) {
                BigDecimal amount=values.get(slice.quantity());
                if(amount==null) {
                    var result=conversion.evaluateFormalRules(user,roles,new SimulationRequest(request.buildingId(),energy.code(),slice.quantity(),energy.unit(),
                            p.method(),p.perspective(),p.consumptionScope(),p.regionCode(),from),to);
                    if(!p.versionId().equals(result.parameterVersionId()) || !"TCE".equals(result.unit()) || result.tce()==null || result.tce().signum()<0)
                        throw error(409,VERSION_CONFLICT,"折标返回版本或单位不一致");
                    amount=result.tce();values.put(slice.quantity(),amount);
                    parameterIds.add(result.parameterVersionId());formulaIds.add(result.formulaVersionId());
                    if(result.standardCoalVersionId()!=null)coalIds.add(result.standardCoalVersionId());
                    trace.add(result.evidence());
                }
                for(Builder b:builders.values()) b.add(energy.code(),slice,amount,config);
            }
        }
        // 指纹固定来源与所选规则，查询不持久化、不宣称替代已封账结果；原生人工来源性质继续保留。
        String fingerprint=digest(List.of(version,trace));
        return view(request,version,"READY",null,nature,fingerprint,List.copyOf(parameterIds),List.copyOf(formulaIds),List.copyOf(coalIds),
                List.copyOf(evidence),options,builders.values().stream().map(Builder::graph).toList());
    }

    private static View view(Request r,ClassificationContracts.VersionView v,String state,String reason,String nature,String fingerprint,
            List<String> parameters,List<String> formulas,List<String> coal,List<String> evidence,List<Option> options,List<Graph> flows) {
        return new View(r.buildingId(),r.month(),"TCE",state,reason,nature,v.versionId(),v.configuration().relationVersionId(),
                fingerprint,parameters,formulas,coal,evidence,options,flows);
    }
    private String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException("Energy flow evidence cannot be encoded",e); }
    }
    private record Energy(String code,String unit) { }
    private record Edge(String source,String target) { }
    private static final class Builder {
        final String scheme;
        String reason;
        BigDecimal total=BigDecimal.ZERO,unallocated=BigDecimal.ZERO,residual=BigDecimal.ZERO;
        final Map<String,Node> nodes=new LinkedHashMap<>();
        final Map<Edge,BigDecimal> links=new LinkedHashMap<>();
        Builder(String scheme,boolean additive) { this.scheme=scheme;if(!additive)reason="交叉分类不可相加形成守恒能流"; }
        void add(String energy,SourceSlice slice,BigDecimal amount,ClassificationContracts.Configuration config) {
            if(reason!=null)return;
            total=total.add(amount);
            if(slice.residual())residual=residual.add(amount);
            List<String> path=new ArrayList<>();path.add(node("ENERGY",energy,energy));
            var targets=slice.classifications().getOrDefault(scheme,Set.of());
            if(targets.size()!=1) {
                path.add(node("UNALLOCATED","UNALLOCATED","未分配"));unallocated=unallocated.add(amount);
            } else {
                Map<String,ClassificationContracts.Node> index=new HashMap<>();
                config.nodes().stream().filter(n->scheme.equals(n.schemeCode())).forEach(n->index.put(n.code(),n));
                String cursor=targets.iterator().next();Set<String> seen=new HashSet<>();List<String> reversed=new ArrayList<>();
                while(cursor!=null && !cursor.isBlank()) {
                    if(!seen.add(cursor)||seen.size()>64||!index.containsKey(cursor)) { reason="分类路径无效";return; }
                    var n=index.get(cursor);reversed.add(node("CLASSIFICATION",cursor,n.name()));cursor=n.parentCode();
                }
                Collections.reverse(reversed);path.addAll(reversed);
            }
            path.add(node("TOTAL","TOTAL","建筑合计"));
            for(int i=1;i<path.size();i++)links.merge(new Edge(path.get(i-1),path.get(i)),amount,BigDecimal::add);
            if(nodes.size()>500||links.size()>2000)reason="能流超过500节点或2000连线上限";
        }
        String node(String type,String code,String name) {
            String id=type+":"+code;nodes.putIfAbsent(id,new Node(id,type,code,name));return id;
        }
        Graph graph() { return new Graph(scheme,reason==null,reason,reason==null?total:null,reason==null?unallocated:null,reason==null?residual:null,
                reason==null?List.copyOf(nodes.values()):List.of(),reason==null?links.entrySet().stream().map(e->new Link(e.getKey().source(),e.getKey().target(),e.getValue())).toList():List.of()); }
    }
}
