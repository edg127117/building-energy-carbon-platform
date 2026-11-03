package com.platform.carbon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.energy.classification.ClassificationCore;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.period.EnergyPeriodAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static com.platform.carbon.CarbonErrors.*;
import static com.platform.carbon.JointFlowConfigurationContracts.*;

/** 审核展示配置与选用分别提交；全部写入和审计处于同一事务，绝不激活正式分类。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class JointFlowConfigurationService {
    private final JointFlowConfigurationRepository repository;
    private final EnergyPeriodAuthorization authorization;
    private final ClassificationService classifications;
    private final ClassificationCore classificationCore;
    private final ObjectMapper mapper;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;

    public List<Version> list(long user,Collection<String> roles,String building) {
        read(user,roles,building);var rows=repository.list(building);
        if(rows.size()>500) throw error(409,LIMIT_EXCEEDED,"展示版本超过500个，请归档管理后查询");
        return rows.stream().map(this::view).toList();
    }
    public Version get(long user,Collection<String> roles,String building,String id) {
        read(user,roles,building);return view(row(building,id));
    }
    public Selection selection(long user,Collection<String> roles,String building) {
        read(user,roles,building);return repository.selection(building);
    }
    @Transactional(rollbackFor=Exception.class)
    public Version create(long user,Collection<String> roles,String building,Create request) {
        write(user,roles,building);text(request.reason(),500);repository.lock(building);
        Configuration configuration=request.copiedFromVersionId()==null?new Configuration(1,null,null,List.of(),List.of(),List.of(),List.of(),"",List.of())
                :view(row(building,request.copiedFromVersionId())).configuration();
        String id=UUID.randomUUID().toString().replace("-","");
        repository.insert(building,id,encode(configuration),hash(configuration),request.reason(),user);
        log(user,building,"CREATE_FLOW_CONFIGURATION",id,request.reason());return view(row(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public Version save(long user,Collection<String> roles,String building,String id,Save request) {
        write(user,roles,building);text(request.reason(),500);repository.lock(building);var row=row(building,id);
        if(row.author()!=user) throw error(403,FORBIDDEN,"只能编辑本人展示草稿");
        validate(request.configuration());validateScope(user,roles,building,request.configuration());
        changed(repository.save(row,revision(request.expectedRevision()),encode(request.configuration()),hash(request.configuration()),request.reason()));
        log(user,building,"SAVE_FLOW_CONFIGURATION",id,request.reason());return view(row(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public Version transition(long user,Collection<String> roles,String building,String id,Transition request,String action) {
        read(user,roles,building);text(request.reason(),500);repository.lock(building);var row=row(building,id);
        boolean submit="submit".equals(action);
        if(submit) {
            authorization.requirePolicyMaintainer(user,roles);
            if(row.author()!=user)throw error(403,FORBIDDEN,"只能提交本人展示草稿");
        } else { authorization.requirePolicyReviewer(user,roles);authorization.requireSeparation(row.author(),user); }
        Configuration configuration=view(row).configuration();
        if(!"reject".equals(action)) { validate(configuration);validateScope(user,roles,building,configuration); }
        String next=switch(action) {case "submit"->"PENDING_REVIEW";case "approve"->"APPROVED";case "reject"->"REJECTED";default->throw invalid("未知审核动作");};
        changed(repository.transition(row,revision(request.expectedRevision()),submit?"DRAFT":"PENDING_REVIEW",next,submit?null:user,request.reason()));
        log(user,building,action.toUpperCase(Locale.ROOT)+"_FLOW_CONFIGURATION",id,request.reason());return view(row(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public Selection select(long user,Collection<String> roles,String building,Select request) {
        write(user,roles,building);text(request.reason(),500);repository.lock(building);var row=row(building,request.versionId());
        if(!"APPROVED".equals(row.status()))throw error(409,STATUS_CONFLICT,"只能选用同建筑已批准展示版本");
        changed(repository.select(building,revision(request.expectedRevision()),row.id()));
        log(user,building,"SELECT_FLOW_CONFIGURATION",row.id(),request.reason());return repository.selection(building);
    }
    public Version resolve(long user,Collection<String> roles,String building,String id,boolean preview) {
        read(user,roles,building);
        if(id==null||id.isBlank()) {
            if(preview)throw invalid("草稿预览必须明确版本");
            id=repository.selection(building).versionId();
        }
        if(id==null)throw error(409,DEPENDENCY_UNAVAILABLE,"建筑尚未选用展示配置");
        Version result=view(row(building,id));
        if(!(preview?"DRAFT":"APPROVED").equals(result.status()))throw error(409,STATUS_CONFLICT,preview?"数值预览只允许草稿版本":"正式大屏仅允许已批准展示版本");
        validate(result.configuration());return result;
    }

    public void validate(Configuration c) {
        if(c==null||c.schemaVersion()!=1||c.startInclusive()==null||c.endExclusive()==null||!c.endExclusive().isAfter(c.startInclusive()))throw invalid("配置版本或日期适用范围无效");
        text(c.sourceReference(),500);
        if(c.relationVersionIds()==null||c.relationVersionIds().isEmpty()||c.relationVersionIds().size()>12||new HashSet<>(c.relationVersionIds()).size()!=c.relationVersionIds().size())throw invalid("适用关系版本须为1至12个不重复版本");
        c.relationVersionIds().forEach(id->text(id,32));
        if(c.dimensions()==null||c.dimensions().isEmpty()||c.dimensions().size()>3||c.dimensions().stream().anyMatch(Objects::isNull))throw invalid("请选择1至3个不重复展示维度");
        Set<String> keys=new HashSet<>();
        for(Dimension d:c.dimensions()) {
            if(!Set.of("ENERGY_TYPE","USAGE","FLOOR","DEVICE_BREAKDOWN").contains(Objects.toString(d.key(),""))||!keys.add(d.key()))throw invalid("未知或重复展示维度");
            text(d.title(),80);
            if("ENERGY_TYPE".equals(d.key())) {if(!"ENERGY_ITEM".equals(d.source())||d.schemeCode()!=null&&!d.schemeCode().isBlank())throw invalid("能源类型必须来自冻结能源项");}
            else {if(!Set.of("FROZEN_SCHEME","MAPPED_SCHEME").contains(Objects.toString(d.source(),"")))throw invalid("归属来源无效");text(d.schemeCode(),64);}
            if("FLOOR".equals(d.key())&&!"FLOOR".equals(d.scopeLevel()))throw invalid("楼层维度须明确楼层级空间证据");
            if(d.nodes()==null||d.nodes().size()>500)throw invalid("节点排序配置超过上限");
            Set<String> nodes=new HashSet<>();
            for(DisplayNode n:d.nodes()) {if(n==null)throw invalid("节点不能为null");text(n.code(),64);text(n.name(),160);if(!nodes.add(n.code())||n.order()<0||n.order()>100000)throw invalid("节点重复或排序无效");}
        }
        if(c.mappings()==null||c.mappings().size()>12)throw invalid("映射配置超过上限");
        Set<String> mapped=new HashSet<>();
        for(var mapping:c.mappings()) {
            if(mapping==null||!c.relationVersionIds().contains(mapping.relationVersionId())||!mapped.add(mapping.relationVersionId()))throw invalid("映射关系版本重复或不属于适用范围");
            classificationCore.validate(mapping);
            Map<String,String> assigned=new HashMap<>();
            for(var binding:mapping.bindings()) {
                String point=binding.meterPointId();
                if((point==null||point.isBlank())&&"POINT".equals(binding.objectType()))point=binding.objectId();
                if(point==null||point.isBlank()||!"CONFIRMED".equals(binding.status())||!"METERING".equals(binding.semantics()))throw invalid("展示映射需要有依据的明确已确认计量测点");
                String old=assigned.putIfAbsent(binding.schemeCode()+"/"+point,binding.nodeCode());
                if(old!=null&&!old.equals(binding.nodeCode()))throw invalid("同测点重复归属不同节点");
            }
            for(Dimension d:c.dimensions())if("MAPPED_SCHEME".equals(d.source())) validateScheme(mapping,d);
        }
        if(c.dimensions().stream().anyMatch(d->"MAPPED_SCHEME".equals(d.source()))&&!mapped.containsAll(c.relationVersionIds()))throw invalid("每个适用关系版本都须有明确映射");
        if(c.floorReferences()==null||c.floorReferences().size()>500)throw invalid("楼层引用超过有界上限");
        Set<String> floorCodes=new HashSet<>(),floorSpaces=new HashSet<>();
        for(FloorReference ref:c.floorReferences()) {
            if(ref==null)throw invalid("楼层引用不能为null");
            text(ref.nodeCode(),64);text(ref.spaceId(),80);
            if(!c.relationVersionIds().contains(ref.relationVersionId())||c.dimensions().stream().noneMatch(d->"FLOOR".equals(d.key())&&d.schemeCode().equals(ref.schemeCode()))
                    ||!floorCodes.add(ref.relationVersionId()+"/"+ref.schemeCode()+"/"+ref.nodeCode())||!floorSpaces.add(ref.relationVersionId()+"/"+ref.spaceId()))throw invalid("楼层引用重复或不属于所选空间方案");
        }
        for(Dimension d:c.dimensions())if("FLOOR".equals(d.key()))for(String relation:c.relationVersionIds())
            if(c.floorReferences().stream().noneMatch(ref->relation.equals(ref.relationVersionId())&&d.schemeCode().equals(ref.schemeCode())))throw invalid("楼层维度缺少历史楼层引用");
        if(c.conversions()==null||c.conversions().size()>64)throw invalid("折标选择超过64项");
        Set<String> parameters=new HashSet<>();
        for(var conversion:c.conversions()) {if(conversion==null)throw invalid("折标选择无效");text(conversion.energyItemCode(),64);text(conversion.inputUnitCode(),64);text(conversion.parameterVersionId(),32);if(!parameters.add(conversion.parameterVersionId()))throw invalid("重复折标参数");}
    }
    static void validateScheme(com.platform.energy.classification.ClassificationContracts.Configuration mapping,Dimension d) {
        var schemes=mapping.schemes().stream().filter(s->d.schemeCode().equals(s.code())).toList();
        Set<String> allowed=switch(d.key()) {case "USAGE"->Set.of("USAGE");case "FLOOR"->Set.of("SPACE");case "DEVICE_BREAKDOWN"->Set.of("USAGE","CIRCUIT");default->Set.of();};
        if(schemes.size()!=1||!"ADDITIVE".equals(schemes.getFirst().mode())||!allowed.contains(schemes.getFirst().dimension()))throw error(409,STATUS_CONFLICT,"展示维度要求明确互斥且语义一致的方案");
    }
    private void validateScope(long user,Collection<String> roles,String building,Configuration c) {
        for(String relation:c.relationVersionIds()) {
            var config=c.mappings().stream().filter(m->relation.equals(m.relationVersionId())).findFirst()
                    .orElse(new com.platform.energy.classification.ClassificationContracts.Configuration(relation,List.of(),List.of(),List.of(),List.of()));
            classifications.validateDisplayMapping(user,roles,building,config);
            Set<String> floorSpaces=new HashSet<>();c.floorReferences().stream().filter(ref->relation.equals(ref.relationVersionId())).forEach(ref->floorSpaces.add(ref.spaceId()));
            if(!floorSpaces.isEmpty())classifications.validateDisplayFloors(user,roles,building,relation,floorSpaces);
        }
    }
    private void read(long user,Collection<String> roles,String building) {text(building,32);authorization.requireReader(roles);authorization.checkBuilding(user,roles,building);}
    private void write(long user,Collection<String> roles,String building) {read(user,roles,building);authorization.requirePolicyMaintainer(user,roles);}
    private JointFlowConfigurationRepository.Row row(String building,String id) {text(id,32);var result=repository.get(building,id);if(result==null)throw error(404,NOT_FOUND,"展示版本不存在或不属于该建筑");return result;}
    private Version view(JointFlowConfigurationRepository.Row row) {
        try {var c=mapper.readValue(row.json(),Configuration.class);if(!Objects.equals(hash(c),row.hash()))throw error(409,DEPENDENCY_UNAVAILABLE,"展示配置摘要损坏");return new Version(row.id(),row.building(),row.number(),row.revision(),row.status(),c,row.hash(),row.author(),row.reviewer());}
        catch(JsonProcessingException e){throw error(409,DEPENDENCY_UNAVAILABLE,"展示配置无法解析");}
    }
    private String hash(Configuration c) {return CarbonCalculationCore.sha256(CarbonEvidence.canonical(mapper.valueToTree(c)));}
    private String encode(Configuration c) {try{return mapper.writeValueAsString(c);}catch(JsonProcessingException e){throw invalid("配置无法序列化");}}
    private void log(long user,String building,String action,String id,String reason) {audit.append(new AuditEvidence("JOINT_FLOW_CONFIGURATION",building,"USER",user,action,"JOINT_FLOW_CONFIGURATION",id,id,null,null,reason,"SUCCESS",null,TraceContext.current(),LocalDateTime.now(),auditProperties.getEnvironmentMode(),auditProperties.isAllowSelfApproval()));}
    private static void changed(int rows){if(rows!=1)throw error(409,VERSION_CONFLICT,"版本状态或修订号已变化");}
    private static long revision(Long value){if(value==null||value<0)throw invalid("修订号无效");return value;}
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw invalid("字段为空或超过长度限制");}
    private static com.platform.framework.exception.BusinessException invalid(String message){return error(400,VALIDATION_FAILED,message);}
}
