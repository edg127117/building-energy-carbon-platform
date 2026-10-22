package com.platform.carbon;

import com.platform.energy.classification.ClassificationCore.SourceSlice;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import static com.platform.carbon.CarbonProjectContracts.*;
import static com.platform.carbon.CarbonErrors.*;

/** 排除在去重后的计量组成上执行；重复分类入口不会造成重复扣减。 */
@Component
public class CarbonProjectCore {
    public record Applied(BigDecimal included,BigDecimal excluded,List<SourceSlice> includedSlices,
                          List<SourceSlice> excludedSlices) { }
    public void validate(Configuration configuration) {
        if(configuration==null||configuration.energies()==null||configuration.exclusions()==null) fail("配置列表不能为空");
        if(configuration.energies().size()>100||configuration.exclusions().size()>500) fail("项目配置超过处理上限");
        Set<String> codes=new HashSet<>();
        for(Energy energy:configuration.energies()) {
            if(energy==null)fail("能源条目不能为空");
            text(energy.energyItemCode(),64);text(energy.pathway(),64);
            if(!Set.of("ENABLED","RESERVED").contains(Objects.toString(energy.capability(),"")))fail("能源能力状态无效");
            // 预留的是路径能力，不占用目录编码；自定义能源编码可以恰好与预留名称相同。
            String identity="ENABLED".equals(energy.capability())?"ENERGY:"+energy.energyItemCode():"RESERVED:"+energy.pathway();
            if(!codes.add(identity))fail("项目能源或预留能力重复");
            if(Set.of("WATER","GREEN_ENERGY").contains(energy.pathway())) {
                if(!"RESERVED".equals(energy.capability()))fail("水和绿色能源只能预留，计算尚未启用");
            } else if(!Set.of("STATIONARY_COMBUSTION","PURCHASED_ELECTRICITY","PURCHASED_HEAT").contains(energy.pathway())) fail("该核算路径不在本轮范围，移动源不可启用");
        }
        if(configuration.energies().stream().anyMatch(e->"ENABLED".equals(e.capability())))text(configuration.classificationVersionId(),32);
        int points=0;
        for(Exclusion exclusion:configuration.exclusions()) {
            if(exclusion==null||exclusion.pointIds()==null||exclusion.pointIds().isEmpty())fail("排除必须明确计量来源");
            text(exclusion.reason(),500);text(exclusion.evidence(),500);
            if(configuration.energies().stream().noneMatch(e->e.energyItemCode().equals(exclusion.energyItemCode())&&"ENABLED".equals(e.capability())))fail("排除引用未启用能源");
            Set<String> unique=new HashSet<>();
            for(String point:exclusion.pointIds()){text(point,80);if(!unique.add(point))fail("同一排除条目来源重复");}
            points+=exclusion.pointIds().size();
        }
        if(points>5000)fail("排除来源超过5000条上限");
    }
    public Applied apply(Configuration configuration,String energy,List<SourceSlice> sources) {
        validate(configuration);
        if(configuration.energies().stream().noneMatch(e->e.energyItemCode().equals(energy)&&"ENABLED".equals(e.capability())))
            throw error(409,DEPENDENCY_UNAVAILABLE,"实际活动量未在项目中启用，不能静默忽略");
        if(sources==null||sources.stream().anyMatch(s->s==null||s.pointId()==null||s.pointId().isBlank()
                ||s.ancestors()==null||!s.ancestors().contains(s.pointId())))
            throw error(409,ACTIVITY_INCOMPLETE,"计量组成缺少有效身份或祖先证据");
        Set<String> excludedPoints=new HashSet<>();
        configuration.exclusions().stream().filter(e->e.energyItemCode().equals(energy)).forEach(e->excludedPoints.addAll(e.pointIds()));
        Set<String> known=new HashSet<>();sources.forEach(s->known.addAll(s.ancestors()));
        if(!known.containsAll(excludedPoints))throw error(409,ACTIVITY_INCOMPLETE,"排除来源没有可验证的独立计量组成，不能从总量盲减");
        List<SourceSlice> included=new ArrayList<>(),excluded=new ArrayList<>();Set<String> identities=new HashSet<>();
        for(SourceSlice source:sources) {
            if(source.quantity()==null||source.quantity().signum()<0||!identities.add(source.pointId()))throw error(409,ACTIVITY_INCOMPLETE,"计量组成重复或用量无效");
            (source.ancestors().stream().anyMatch(excludedPoints::contains)?excluded:included).add(source);
        }
        return new Applied(sum(included),sum(excluded),List.copyOf(included),List.copyOf(excluded));
    }
    private static BigDecimal sum(List<SourceSlice> values){return values.stream().map(SourceSlice::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static void text(String value,int limit){if(value==null||value.isBlank()||value.length()>limit)fail("配置文本缺失或超长");}
    private static void fail(String message){throw error(400,VALIDATION_FAILED,message);}
}
