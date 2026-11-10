package com.platform.carbon;

import com.platform.energy.catalog.*;
import com.platform.energy.classification.*;
import com.platform.energy.aggregation.*;
import com.platform.energy.aggregation.EnergyAggregationModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.carbon.DynamicElectricityCore.problem;

/** 设备周期量复用能源目录、原生周期求和、计量去重和项目排除，再匹配每表同区间因子。 */
@Service
@RequiredArgsConstructor
public class DynamicElectricityLiveInput {
    private final CarbonProjectService projects;
    private final CarbonProjectCore projectCore;
    private final ClassificationService classifications;
    private final ClassificationCore classificationCore;
    private final EnergyCatalogService catalog;
    private final EnergyCatalogLookup lookup;
    private final EnergyAggregationCore aggregation;

    /** 缓存只属于一次有界后台批次，身份或配置摘要变化立即清空；有效期跨界仍重新解析。 */
    static final class Capture {
        private String scope;
        private final Map<String,BindingCapture> bindings=new HashMap<>();
        private final Map<String,EnergyCatalogLookup.ApprovedEnergyItem> items=new HashMap<>();
        void pin(String key) {if(!Objects.equals(scope,key)){bindings.clear();items.clear();scope=key;}}
    }
    private record BindingCapture(com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView binding,
                                  Instant from,Instant to) { }

    DynamicElectricityCore.Applied calculate(DynamicElectricityLiveRepository.Task task,Collection<String> roles,Capture capture) {
        Instant from=Instant.ofEpochMilli(task.from()),to=Instant.ofEpochMilli(task.to());
        var project=projects.effective(task.building(),from,to);
        if(project==null)throw problem("DYNAMIC_PROJECT_MISSING","时段碳项目未生效");
        var codes=project.configuration().energies().stream().filter(e->"ENABLED".equals(e.capability())&&"PURCHASED_ELECTRICITY".equals(e.pathway()))
                .map(CarbonProjectContracts.Energy::energyItemCode).distinct().toList();
        if(codes.isEmpty()||codes.size()>16)throw problem("DYNAMIC_PROJECT_MISSING","外购电力项目缺失或超限");
        List<DynamicElectricityInputReader.Contribution> contributions=new ArrayList<>();
        Set<String> points=new HashSet<>();
        for(String code:codes) {
            var context=classifications.monitoringIntervalContext(task.user(),roles,task.building(),project.configuration().classificationVersionId(),from,to,code);
            if(context.meters().size()>500)throw problem("DYNAMIC_CAPACITY","设备时段超过500个测点");
            var item=capture.items.get(code);
            if(item==null||item.effectiveFrom().isAfter(local(from))||item.effectiveTo()!=null&&item.effectiveTo().isBefore(local(to))) {
                item=lookup.findApprovedItem(code,local(from)).orElseThrow(()->problem("DYNAMIC_BINDING_INVALID","外购电力目录缺失"));
                capture.items.put(code,item);
            }
            List<ClassificationCore.Fact> facts=new ArrayList<>();
            for(var meter:context.meters()) {
                if(!points.add(meter.pointId()))throw problem("DYNAMIC_DUPLICATE_POINT","外购电力能源重复引用测点");
                var reading=task.readings().get(meter.pointId());
                if(reading==null||reading.quantity()==null||reading.factor()==null||reading.quantityRevision()!=reading.factorRevision())continue;
                var binding=binding(task,roles,meter.pointId(),from,to,capture);
                if(!code.equals(binding.energyItemCode())||!binding.rawUnit().equalsIgnoreCase("KWH")
                        ||!"ELECTRICITY".equals(item.compatibleCategory())||!item.usageScopes().contains("PURCHASED_ELECTRICITY")
                        ||!item.versionId().equals(binding.energyItemVersionId()))throw problem("DYNAMIC_BINDING_INVALID","设备量与有效外购电力目录不一致");
                var quantity=aggregation.aggregateNativePeriodTotal(new AggregationQuery(task.building(),meter.pointId(),from,to,to),
                        List.of(new ActivityFact(meter.pointId()+"@"+task.from(),reading.quantity(),to,to,"UPSTREAM_DECLARED","DEVICE_PERIOD_V1",false,from,to,"Asia/Shanghai","QUARTER_HOUR_V1")));
                facts.add(new ClassificationCore.Fact(meter.pointId(),quantity,meter.pointId()+":"+reading.quantityRevision(),reading.nature(),true));
            }
            var computation=classificationCore.compute(context.configuration(),context.configuration().schemes().getFirst().code(),List.of(),
                    new ClassificationCore.Input(context.meters(),facts,context.resolvedBindings()));
            if(!"CALCULABLE".equals(computation.status()))throw problem("DYNAMIC_INPUT_WAITING","计量边界内必需电量和因子未配齐");
            var applied=projectCore.apply(project.configuration(),code,computation.sourceSlices());
            for(var slice:applied.includedSlices())contributions.add(new DynamicElectricityInputReader.Contribution(slice.pointId(),slice.ancestors(),slice.quantity(),slice.evidenceIds()));
        }
        var factors=task.readings().entrySet().stream().filter(e->e.getValue().factor()!=null).map(e->new DynamicElectricityCore.Factor(
                e.getKey()+":"+e.getValue().factorRevision(),e.getKey(),from,to,e.getValue().factor(),e.getValue().nature(),e.getValue().reference(),"UPSTREAM","DEVICE_REPORTED")).toList();
        return DynamicElectricityCore.calculate(contributions,factors,from,to);
    }
    private com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView binding(
            DynamicElectricityLiveRepository.Task task,Collection<String> roles,String point,Instant from,Instant to,Capture capture) {
        var cached=capture.bindings.get(point);
        if(cached!=null&&!cached.from().isAfter(from)&&!cached.to().isBefore(to))return cached.binding();
        Instant dayStart=from.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant dayEnd=dayStart.plusSeconds(86400);
        Instant ended=Instant.ofEpochSecond(Instant.now().getEpochSecond()/900*900);
        Instant readEnd=dayEnd.isAfter(ended)?ended:dayEnd;
        try {
            var value=catalog.effectiveBindingForPeriod(task.user(),roles,task.building(),point,local(dayStart),local(readEnd));
            capture.bindings.put(point,new BindingCapture(value,dayStart,readEnd));
            return value;
        } catch(com.platform.framework.exception.BusinessException ex) {
            // 日内换版不能阻止可验证的单个设备时段；仅依赖不覆盖的409回到准确区间，权限或存储错误直接传播。
            if(ex.getCode()!=409)throw ex;
            var value=catalog.effectiveBindingForPeriod(task.user(),roles,task.building(),point,local(from),local(to));
            capture.bindings.put(point,new BindingCapture(value,from,to));
            return value;
        }
    }
    private static LocalDateTime local(Instant value){return LocalDateTime.ofInstant(value,ZoneId.of("Asia/Shanghai"));}
}
