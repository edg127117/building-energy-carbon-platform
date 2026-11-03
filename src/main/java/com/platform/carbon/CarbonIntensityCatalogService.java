package com.platform.carbon;

import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static com.platform.carbon.CarbonIntensityContracts.*;

/** 集中目录维护不触发因子计算或审批；已有碳规则维护职责仍是写入边界。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class CarbonIntensityCatalogService {
    private final CarbonIntensityCatalogRepository repository;
    private final BackendDutyService duties;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;
    public List<Version> list() {
        var versions=repository.versions();
        if(versions.size()>1000) throw error(409,"CATALOG_LIMIT","目录超过查询上限，请归档整理后查询");
        for(Version version:versions) if(!contentHash(version.name(),version.documentVersion(),version.sourceReference(),version.publishedOn(),version.entries()).equals(version.evidenceHash()))
            throw error(409,"CATALOG_EVIDENCE_INVALID","目录内容与版本摘要不一致，请核对存储记录");
        return versions;
    }
    @Transactional(rollbackFor=Exception.class)
    public Version save(long user, Collection<String> roles, Save input) {
        if(roles==null || roles.stream().noneMatch(r->Set.of("ENERGY_MANAGER","PLATFORM_ADMIN").contains(r)))
            throw error(403,"CATALOG_FORBIDDEN","缺少目录维护角色");
        duties.requireDuty(user,BackendDuty.CARBON_RULE_MAINTAIN);
        if(input==null || blank(input.name()) || input.name().length()>200 || blank(input.sourceReference())
                || input.sourceReference().length()>1000 || blank(input.documentVersion()) || input.documentVersion().length()>100
                || input.entries()==null || input.entries().isEmpty() || input.entries().size()>200)
            throw error(400,"CATALOG_INVALID","标准名称、来源、文件版本及1至200条指标必填");
        input.entries().forEach(CarbonIntensityCatalogService::validate);
        String catalog=blank(input.catalogId())?id():input.catalogId();
        if(catalog.length()>32) throw error(400,"CATALOG_INVALID","目录标识无效");
        Version previous=repository.latestForUpdate(catalog);
        if(previous==null ? !blank(input.expectedVersionId()) : !previous.versionId().equals(input.expectedVersionId()))
            throw error(409,"CATALOG_VERSION_CONFLICT","目录已更新，请刷新后新增版本");
        // 已明确适用期的旧条目不会被新版本覆盖；相同起始日改值会造成历史含义改变，应拒绝。
        if(previous!=null) for(Version old:list()) if(old.catalogId().equals(catalog)) for(Rule a:old.entries()) {
            if(!"KNOWN".equals(a.timeStatus())) continue;
            for(Rule b:input.entries()) if(sameScope(a,b) && Objects.equals(a.applicableFrom(),b.applicableFrom()) && !a.equals(b))
                throw error(409,"CATALOG_HISTORY_CONFLICT","已明确适用期的历史条目不能覆盖；请为新依据填写新的适用起始时间");
        }
        String hash=contentHash(input.name().trim(),input.documentVersion().trim(),input.sourceReference().trim(),input.publishedOn(),input.entries());
        Version value=new Version(catalog,id(),previous==null?1:previous.revision()+1,input.name().trim(),input.documentVersion().trim(),
                input.sourceReference().trim(),input.publishedOn(),user,null,hash,List.copyOf(input.entries()));
        try { repository.insert(value); } catch(DuplicateKeyException e) { throw error(409,"CATALOG_VERSION_CONFLICT","目录版本竞争，请刷新后重试"); }
        audit.append(new AuditEvidence("CARBON_MANAGEMENT",null,"USER",user,"CREATE_INTENSITY_STANDARD_VERSION",
                "CARBON_INTENSITY_STANDARD",catalog,value.versionId(),null,previous==null?null:previous.versionId(),
                "revision="+value.revision()+";entries="+value.entries().size()+";hash="+hash,
                "SUCCESS",null,TraceContext.current(),LocalDateTime.now(),auditProperties.getEnvironmentMode(),false));
        return value;
    }
    static void validate(Rule r) {
        if(r==null || r.regionPrefix()==null || !r.regionPrefix().matches("[0-9]{2}([0-9]{2}){0,2}")
                || blank(r.buildingCategory()) || r.buildingCategory().length()>30
                || (r.subcategory()!=null && r.subcategory().length()>60) || (r.climateLabel()!=null && r.climateLabel().length()>100)
                || (r.boundaryNote()!=null && r.boundaryNote().length()>1000) || (r.areaNote()!=null && r.areaNote().length()>1000)
                || !Set.of("WHOLE_BUILDING_ENERGY","STANDARD_SPECIFIC","UNKNOWN").contains(r.boundaryCode()==null?"":r.boundaryCode())
                || !Set.of("BUILDING_AREA","UNKNOWN").contains(r.areaBasis()==null?"":r.areaBasis())
                || !Set.of("CO2_ONLY","GHG_CO2E").contains(r.gasBasis()==null?"":r.gasBasis())
                || !Set.of("KNOWN","NOT_STATED","PENDING").contains(r.timeStatus()==null?"":r.timeStatus())
                || !positive(r.constraintValue()) || r.baselineValue()==null || r.guideValue()==null || r.guideValue().signum()<0
                || r.constraintValue().compareTo(r.baselineValue())<0 || r.baselineValue().compareTo(r.guideValue())<0)
            throw error(400,"CATALOG_INVALID","地区、分类、分区、口径和三个参考值无效，须满足约束值≥基准值≥引导值≥0且约束值大于0");
        if("KNOWN".equals(r.timeStatus())) {
            if(r.applicableFrom()==null || (r.applicableTo()!=null && !r.applicableTo().isAfter(r.applicableFrom())))
                throw error(400,"CATALOG_INVALID","已明确时间须填写起始日，结束日为不含边界且须晚于起始日");
        } else if(r.applicableFrom()!=null || r.applicableTo()!=null)
            throw error(400,"CATALOG_INVALID","时间未明确时不接受推定日期");
        for(BigDecimal number:List.of(r.constraintValue(),r.baselineValue(),r.guideValue()))
            if(number.precision()>20 || Math.max(0,number.scale())>6) throw error(400,"CATALOG_INVALID","参考值最多20位有效数字、6位小数");
    }
    static boolean sameScope(Rule a,Rule b) { return Objects.equals(a.regionPrefix(),b.regionPrefix()) && Objects.equals(a.buildingCategory(),b.buildingCategory()) && Objects.equals(a.subcategory(),b.subcategory()); }
    static String contentHash(String name,String documentVersion,String source,java.time.LocalDate published,List<Rule> entries) {
        return CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(Arrays.asList(name,documentVersion,source,published,entries))));
    }
    static boolean blank(String value) { return value==null || value.isBlank(); }
    private static boolean positive(BigDecimal value) { return value!=null && value.signum()>0; }
    static String id() { return UUID.randomUUID().toString().replace("-",""); }
    static BusinessException error(int status,String code,String message) { return new BusinessException(status,"CARBON_INTENSITY_"+code,message); }
}
