package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonErrors.*;
import static com.platform.carbon.CarbonProjectContracts.*;

/** 项目能源与碳边界使用独立职责和版本；生效只触发重算候选，不覆盖旧报告。 */
@Service
@Transactional(readOnly=true)
public class CarbonProjectService {
    private final CarbonProjectRepository repository;
    private final CarbonAuthorization authorization;
    private final CarbonProjectCore core;
    private final ClassificationService classifications;
    private final EnergyCatalogService catalog;
    private final ObjectMapper json;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;
    private final CarbonRuleRepository rules;
    public CarbonProjectService(CarbonProjectRepository repository,CarbonAuthorization authorization,
                                CarbonProjectCore core,ClassificationService classifications,
                                EnergyCatalogService catalog,ObjectMapper json,AuditEvidenceWriter audit,
                                AuditGovernanceProperties auditProperties,CarbonRuleRepository rules) {
        this.repository=repository;this.authorization=authorization;this.core=core;this.classifications=classifications;
        this.catalog=catalog;this.json=json;this.audit=audit;this.auditProperties=auditProperties;this.rules=rules;
    }
    public List<Version> versions(long user,Collection<String> roles,String building) {
        read(user,roles,building);return rows(building).stream().map(this::view).toList();
    }
    public List<PathOption> options(long user,Collection<String> roles,String building) {
        read(user,roles,building);
        return List.of(new PathOption("STATIONARY_COMBUSTION","ENABLED","SCOPE_1"),
                new PathOption("PURCHASED_ELECTRICITY","ENABLED","SCOPE_2"),new PathOption("PURCHASED_HEAT","ENABLED","SCOPE_2"),
                new PathOption("WATER","RESERVED","SCOPE_3"),new PathOption("GREEN_ENERGY","RESERVED",null));
    }
    @Transactional(rollbackFor=Exception.class)
    public Version create(long user,Collection<String> roles,String building,Create request) {
        maintain(user,roles,building);reason(request.reason());repository.lock(building);
        if(rows(building).stream().anyMatch(r->Set.of("DRAFT","PENDING_REVIEW","APPROVED").contains(r.status())))conflict();
        Configuration configuration=request.copiedFromVersionId()==null?new Configuration(null,List.of(),List.of()):decode(require(building,request.copiedFromVersionId()).json());
        String id=id();repository.insert(id,building,encode(configuration),request.reason(),user);
        log(user,building,"CREATE_CARBON_PROJECT",id,request.reason());return view(require(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public Version save(long user,Collection<String> roles,String building,String id,Save request) {
        maintain(user,roles,building);reason(request.reason());repository.lock(building);
        var row=require(building,id);author(row,user);core.validate(request.configuration());validateReferences(user,roles,building,request.configuration());
        if(repository.save(row,request.expectedRevision(),encode(request.configuration()),request.reason())!=1)conflict();
        log(user,building,"SAVE_CARBON_PROJECT",id,request.reason());return view(require(building,id));
    }
    public Readiness readiness(long user,Collection<String> roles,String building,String id) {
        read(user,roles,building);var row=require(building,id);var config=decode(row.json());List<Issue> issues=new ArrayList<>();
        try {core.validate(config);validateReferences(user,roles,building,config);}
        catch(com.platform.framework.exception.BusinessException exception) {
            if(exception.getCode()==401||exception.getCode()==403)throw exception;
            issues.add(new Issue("CONFIGURATION_INVALID",id,exception.getMessage()));
        }
        if(config.energies().isEmpty())issues.add(new Issue("ENERGY_REQUIRED","energies","尚未选择项目能源"));
        if(config.classificationVersionId()==null&&config.energies().stream().anyMatch(e->"ENABLED".equals(e.capability())))
            issues.add(new Issue("CLASSIFICATION_REQUIRED","classificationVersionId","尚未关联已审核分类版本"));
        else if(config.classificationVersionId()!=null) {
            try {
                var classification=classifications.version(user,roles,building,config.classificationVersionId());
                if(!"APPROVED".equals(classification.status()))issues.add(new Issue("CLASSIFICATION_PENDING",classification.versionId(),"分类配置尚未审核"));
                classification.issues().forEach(i->issues.add(new Issue(i.code(),i.reference(),i.message())));
            } catch(com.platform.framework.exception.BusinessException exception) {
                if(exception.getCode()==401||exception.getCode()==403)throw exception;
                issues.add(new Issue("CLASSIFICATION_UNAVAILABLE",config.classificationVersionId(),exception.getMessage()));
            }
        }
        config.energies().stream().filter(e->"RESERVED".equals(e.capability())).forEach(e->issues.add(new Issue("RESERVED",e.energyItemCode(),"仅预留，未启用核算")));
        return new Readiness(building,id,issues.stream().allMatch(i->"RESERVED".equals(i.code())),List.copyOf(issues));
    }
    @Transactional(rollbackFor=Exception.class)
    public Version transition(long user,Collection<String> roles,String building,String id,String action,Transition request) {
        read(user,roles,building);reason(request.reason());repository.lock(building);var row=require(building,id);
        String from,to;Long reviewer=row.reviewer();
        switch(action) {
            case "submit" -> {maintain(user,roles,building);author(row,user);from="DRAFT";to="PENDING_REVIEW";requireReady(user,roles,building,id);}
            case "withdraw" -> {maintain(user,roles,building);author(row,user);from="PENDING_REVIEW";to="DRAFT";}
            case "approve","reject" -> {authorization.requireRuleReviewer(user,roles,building);authorization.requireSeparation(row.author(),user);from="PENDING_REVIEW";to=action.equals("approve")?"APPROVED":"DRAFT";reviewer=action.equals("approve")?user:null;if(action.equals("approve"))requireReady(user,roles,building,id);}
            default -> throw error(400,VALIDATION_FAILED,"版本操作无效");
        }
        if(repository.transition(row,request.expectedRevision(),from,to,request.reason(),reviewer)!=1)conflict();
        log(user,building,"CARBON_PROJECT_"+action.toUpperCase(Locale.ROOT),id,request.reason());return view(require(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public Version activate(long user,Collection<String> roles,String building,String id,Transition request) {
        read(user,roles,building);authorization.requireRuleActivator(user,roles,building);reason(request.reason());repository.lock(building);
        var row=require(building,id);Instant from=request.effectiveFrom();
        if(from==null||from.isAfter(Instant.now())||!from.equals(Instant.ofEpochMilli(from.toEpochMilli())))throw error(400,VALIDATION_FAILED,"生效时间必须明确且不晚于当前时间，精度最多毫秒");
        var old=rows(building).stream().filter(r->"ACTIVE".equals(r.status())).findFirst().orElse(null);
        if(old!=null&&!from.isAfter(Instant.ofEpochMilli(old.from())))conflict();
        requireReady(user,roles,building,id);
        Configuration configuration=decode(row.json());
        if(configuration.classificationVersionId()!=null) {
            var classification=classifications.version(user,roles,building,configuration.classificationVersionId());
            if(classification.effectiveFrom()==null||classification.effectiveFrom().isAfter(from)
                    ||(classification.effectiveTo()!=null&&!classification.effectiveTo().isAfter(from)))
                throw error(409,DEPENDENCY_UNAVAILABLE,"项目生效时刻不在所引用分类版本的有效范围");
        }
        if(repository.activate(row,request.expectedRevision(),from,user,request.reason())!=1)conflict();
        String fingerprint=CarbonCalculationCore.sha256(building+"|"+id+"|"+from);
        rules.insertDependencyChange(id(),"ACTIVITY_SNAPSHOT","CARBON_PROJECT_VERSION",building,
                "项目能源或碳排除变化，需核对候选与原报告",old==null?null:old.id(),id,fingerprint,
                building,null,LocalDateTime.ofInstant(from,ZoneId.of("Asia/Shanghai")),null,user,LocalDateTime.now());
        log(user,building,"ACTIVATE_CARBON_PROJECT",id,request.reason());return view(require(building,id));
    }
    /** 返回覆盖整个来源期间的冻结版本；启用后出现月内切换时拒绝按天摊分。 */
    public Version effective(String building,Instant from,Instant to) {
        var active=rows(building).stream().filter(r->r.from()!=null).toList();
        var matched=active.stream().filter(r->r.from()<=from.toEpochMilli()&&(r.to()==null||r.to()>=to.toEpochMilli())).toList();
        if(matched.size()==1)return view(matched.getFirst());
        if(!matched.isEmpty()||active.stream().anyMatch(r->r.from()<to.toEpochMilli()))throw error(409,ACTIVITY_INCOMPLETE,"项目配置未覆盖完整期间，请按生效时间提供可验证的封账来源");
        return null;
    }
    public record Projection(Version version,ClassificationService.SealedProjection source,CarbonProjectCore.Applied applied) { }
    public Projection project(String building,Instant from,Instant to,String energy,String unit,List<PeriodSnapshot> snapshots) {
        Version version=effective(building,from,to);if(version==null)return null;
        if(version.configuration().energies().stream().noneMatch(e->energy.equals(e.energyItemCode())&&"ENABLED".equals(e.capability())))
            throw error(409,DEPENDENCY_UNAVAILABLE,"实际活动量未在项目中启用，不能静默忽略");
        var source=classifications.projectSealed(0L,List.of("PLATFORM_ADMIN"),building,version.configuration().classificationVersionId(),from,to,energy,unit,snapshots);
        return new Projection(version,source,core.apply(version.configuration(),energy,source.slices()));
    }
    private void requireReady(long user,Collection<String> roles,String building,String id) {
        Configuration configuration=decode(require(building,id).json());core.validate(configuration);validateReferences(user,roles,building,configuration);
        Readiness readiness=readiness(user,roles,building,id);if(!readiness.ready())throw error(409,DEPENDENCY_UNAVAILABLE,"项目配置仍有阻塞项，请先查看就绪检查");
    }
    private void validateReferences(long user,Collection<String> roles,String building,Configuration configuration) {
        if(configuration.classificationVersionId()!=null)classifications.version(user,roles,building,configuration.classificationVersionId());
        var items=catalog.listItems(roles);
        for(Energy energy:configuration.energies())if("ENABLED".equals(energy.capability())) {
            if(items.stream().noneMatch(i->i.itemCode().equals(energy.energyItemCode())&&"APPROVED".equals(i.status())&&i.usageScopes().contains(energy.pathway())
                    &&switch(energy.pathway()){case "PURCHASED_ELECTRICITY"->"ELECTRICITY".equals(i.compatibleCategory());case "PURCHASED_HEAT"->"HEAT".equals(i.compatibleCategory());default->Set.of("NATURAL_GAS","FUEL").contains(i.compatibleCategory());}))
                throw error(400,VALIDATION_FAILED,"能源品种与审核目录或核算路径不一致");
        }
        if(!configuration.exclusions().isEmpty()) {
            var classification=classifications.version(user,roles,building,configuration.classificationVersionId());
            Set<String> declared=new HashSet<>();classification.configuration().bindings().forEach(b->{if("POINT".equals(b.objectType()))declared.add(b.objectId());if(b.meterPointId()!=null)declared.add(b.meterPointId());});
            if(configuration.exclusions().stream().flatMap(e->e.pointIds().stream()).anyMatch(p->!declared.contains(p)))throw error(400,VALIDATION_FAILED,"排除必须引用分类配置内明确的计量测点，不能填写任意来源");
        }
    }
    private void read(long user,Collection<String> roles,String building){if(roles==null||Collections.disjoint(roles,List.of("BUILDING_OWNER","ENERGY_MANAGER","PLATFORM_ADMIN")))throw error(403,FORBIDDEN,"角色无权访问项目配置");authorization.requireReader(user,roles,building);if(!repository.buildingExists(building))throw error(404,NOT_FOUND,"建筑不存在");}
    private void maintain(long user,Collection<String> roles,String building){read(user,roles,building);authorization.requireRuleMaintainer(user,roles,building);}
    private List<CarbonProjectRepository.Row> rows(String building){var values=repository.versions(building);if(values.size()>100)throw error(409,LIMIT_EXCEEDED,"项目版本超过100条，请联系管理员归档");return values;}
    private CarbonProjectRepository.Row require(String building,String id){return rows(building).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->error(404,NOT_FOUND,"项目版本不存在"));}
    private Version view(CarbonProjectRepository.Row r){return new Version(r.id(),r.building(),r.number(),r.revision(),r.status(),r.from()==null?null:Instant.ofEpochMilli(r.from()),r.to()==null?null:Instant.ofEpochMilli(r.to()),r.author(),r.reviewer(),decode(r.json()));}
    private Configuration decode(String value){try{
        Configuration configuration=json.readValue(value,Configuration.class);
        // 持久化损坏也须在公开边界拒绝，不能把空列表或非法条目传给页面和就绪计算。
        core.validate(configuration);return configuration;
    }catch(Exception e){throw error(409,DEPENDENCY_UNAVAILABLE,"项目配置存储无效");}}
    private String encode(Configuration value){try{return json.writeValueAsString(value);}catch(Exception e){throw error(400,VALIDATION_FAILED,"项目配置无法序列化");}}
    private void author(CarbonProjectRepository.Row row,long user){if(row.author()!=user)throw error(403,FORBIDDEN,"只能修改本人草稿");}
    private static void reason(String reason){if(reason==null||reason.isBlank()||reason.length()>500)throw error(400,VALIDATION_FAILED,"操作原因必填且不能超过500字");}
    private static void conflict(){throw error(409,VERSION_CONFLICT,"项目版本状态或修订已变化");}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private void log(long user,String building,String action,String id,String reason){audit.append(new AuditEvidence("CARBON_MANAGEMENT",building,"USER",user,action,"CARBON_PROJECT",id,id,null,null,reason,"SUCCESS",null,TraceContext.current(),LocalDateTime.now(),auditProperties.getEnvironmentMode(),false));}
}
