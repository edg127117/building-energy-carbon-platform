package com.platform.carbon;

import com.platform.hvac.model.entity.Building;
import java.time.LocalDate;
import java.util.*;
import static com.platform.carbon.CarbonIntensityContracts.*;

/** 地区前缀是目录明确维护的行政范围，不将建筑名称或气候大区推断成评价类别。 */
final class CarbonIntensityMatcher {
    private CarbonIntensityMatcher() { }
    record Match(String status, Reference reference) { }
    static Match match(List<Version> versions,Building building,LocalDate start,LocalDate end) {
        if(building==null || building.getRegionCode()==null || !building.getRegionCode().matches("[0-9]{6}")
                || (CarbonIntensityCatalogService.blank(building.getBuildingType()) && CarbonIntensityCatalogService.blank(building.getCarbonEvaluationCategory())))
            return new Match("BUILDING_PROFILE_INCOMPLETE",null);
        String type=building.getCarbonEvaluationCategory()==null || building.getCarbonEvaluationCategory().isBlank()
                ?building.getBuildingType().trim():building.getCarbonEvaluationCategory().trim();
        if(Set.of("综合","综合用途","MIXED").contains(type)) return new Match("BUILDING_CATEGORY_AMBIGUOUS",null);
        List<Reference> known=new ArrayList<>(),undated=new ArrayList<>();
        Set<String> undatedFamilies=new HashSet<>();
        Map<String,Integer> heads=new HashMap<>();
        versions.forEach(v->heads.merge(v.catalogId(),v.revision(),Math::max));
        Map<String,List<Reference>> families=new HashMap<>();
        for(Version v:versions) for(Rule r:v.entries()) {
            if(!building.getRegionCode().startsWith(r.regionPrefix()) || !type.equals(r.buildingCategory())) continue;
            if(r.subcategory()!=null && !r.subcategory().isBlank()) {
                if(building.getCarbonEvaluationSubcategory()==null || building.getCarbonEvaluationSubcategory().isBlank())
                    return new Match("BUILDING_SUBCATEGORY_REQUIRED",null);
                if(!r.subcategory().equals(building.getCarbonEvaluationSubcategory().trim())) continue;
            }
            Reference ref=new Reference(v.versionId(),v.evidenceHash(),v.name(),v.documentVersion(),v.sourceReference(),v.publishedOn(),r,
                    "CO2_ONLY".equals(r.gasBasis())?"kgCO2/(m²·年)":"kgCO2e/(m²·年)");
            String familyKey=v.catalogId()+"/"+r.regionPrefix()+"/"+r.buildingCategory()+"/"+Objects.toString(r.subcategory(),"");
            if(!"KNOWN".equals(r.timeStatus())) {
                if(heads.get(v.catalogId())==v.revision()) { undated.add(ref);undatedFamilies.add(familyKey); }
                continue;
            }
            // 已过期的新版本也参与确定继任关系，防止旧的开放结束日条目重新生效。
            if(r.applicableFrom().isBefore(end))
                families.computeIfAbsent(familyKey,key->new ArrayList<>()).add(ref);
        }
        for(var entry:families.entrySet()) {
            // 同一条目最新维护版本日期未知时，用它作参考；不让旧版本再成为第二个候选，也不宣称历史适用。
            if(undatedFamilies.contains(entry.getKey())) continue;
            var family=entry.getValue();
            LocalDate latest=family.stream().map(r->r.rule().applicableFrom()).max(LocalDate::compareTo).orElseThrow();
            var entries=family.stream().filter(r->r.rule().applicableFrom().equals(latest)).toList();
            if(latest.isAfter(start)) return new Match("PERIOD_VERSION_CHANGE",entries.getFirst());
            // 同一目录原样复制的条目去重，其他重叠条目必须明确报告冲突。
            var unique=new LinkedHashMap<Rule,Reference>(); entries.forEach(r->unique.putIfAbsent(r.rule(),r));
            for(var ref:unique.values()) if(ref.rule().applicableTo()==null || !ref.rule().applicableTo().isBefore(end)) known.add(ref);
        }
        if(known.size()+undated.size()>1) return new Match("MULTIPLE_APPLICABLE_REFERENCES",null);
        if(!undated.isEmpty()) return new Match("APPLICABILITY_TIME_UNCLEAR",undated.getFirst());
        if(known.size()==1) return new Match("MATCHED",known.getFirst());
        return new Match("NO_APPLICABLE_REFERENCE",null);
    }
}
