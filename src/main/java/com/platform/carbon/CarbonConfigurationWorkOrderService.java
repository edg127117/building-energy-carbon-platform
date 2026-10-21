package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.classification.ClassificationContracts;
import com.platform.energy.classification.ClassificationService;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

import static com.platform.carbon.CarbonErrors.*;

/** 工作单只编排一次明确的域操作；独立审核、职责与生效校验始终由原领域服务执行。 */
@Service
public class CarbonConfigurationWorkOrderService {
    public record WorkOrder(CarbonConfigurationGuideService.Guide guide,String fingerprint) { }
    public record Command(@NotBlank String fingerprint,@NotBlank String domain,@NotBlank String action,
                          @NotBlank @Size(max=500) String reason,Instant effectiveFrom,
                          @NotBlank @Size(max=80) String idempotencyKey) { }
    public record Receipt(String buildingId,String projectVersionId,String domain,String action,
                          String reference,String state,long revision) { }
    private final CarbonConfigurationGuideService guides;
    private final CarbonProjectService projects;
    private final ClassificationService classifications;
    private final RelationGovernanceService relations;
    private final ObjectMapper mapper;
    private final CarbonProjectRepository projectRepository;
    private final CarbonWorkOrderRepository commands;
    private final com.platform.audit.AuditEvidenceWriter audit;
    private final com.platform.audit.AuditGovernanceProperties auditProperties;
    public CarbonConfigurationWorkOrderService(CarbonConfigurationGuideService guides,CarbonProjectService projects,
            ClassificationService classifications,RelationGovernanceService relations,ObjectMapper mapper,
            CarbonProjectRepository projectRepository,CarbonWorkOrderRepository commands,
            com.platform.audit.AuditEvidenceWriter audit,com.platform.audit.AuditGovernanceProperties auditProperties) {
        this.guides=guides;this.projects=projects;this.classifications=classifications;this.relations=relations;this.mapper=mapper;
        this.projectRepository=projectRepository;this.commands=commands;this.audit=audit;this.auditProperties=auditProperties;
    }
    public WorkOrder read(long user,Collection<String> roles,String building,String version) {
        var guide=guides.guide(user,roles,building,version);
        try{return new WorkOrder(guide,com.platform.energy.period.EnergyPeriodCalculationService.digest(mapper.writeValueAsString(guide)));}
        catch(Exception e){throw new IllegalStateException("Configuration work order serialization failed",e);}
    }
    @Transactional(rollbackFor=Exception.class)
    public Receipt execute(long user,Collection<String> roles,String building,String version,Command command) {
        if(roles==null||Collections.disjoint(roles,List.of("ENERGY_MANAGER","PLATFORM_ADMIN")))throw error(403,FORBIDDEN,"无权执行工作单操作");
        if(projects.versions(user,roles,building).stream().noneMatch(v->v.versionId().equals(version)))throw error(404,NOT_FOUND,"项目版本不存在");
        if(command.reason()==null||command.reason().isBlank()||command.reason().length()>500||command.idempotencyKey()==null||command.idempotencyKey().isBlank()||command.idempotencyKey().length()>80)
            throw error(400,VALIDATION_FAILED,"操作原因或幂等键无效");
        // 同一建筑命令串行化；域变更、回执和审计在同一事务，失败不能留下部分成功。
        projectRepository.lock(building);
        String hash=hash(command);
        var previous=commands.find(building,version,command.idempotencyKey());
        if(previous!=null) {
            if(previous.author()!=user||!previous.hash().equals(hash))throw error(409,IDEMPOTENCY_CONFLICT,"工作单幂等键已被其他内容或操作者使用");
            try{return mapper.readValue(previous.json(),Receipt.class);}catch(Exception e){throw new IllegalStateException("Stored work order receipt invalid",e);}
        }
        var order=read(user,roles,building,version);
        if(!order.fingerprint().equals(command.fingerprint()))throw error(409,VERSION_CONFLICT,"配置依赖已变化，请刷新工作单");
        Receipt receipt=apply(user,roles,building,version,command,order);
        String id=UUID.randomUUID().toString().replace("-","");
        try{commands.insert(id,building,version,command.idempotencyKey(),hash,order.fingerprint(),mapper.writeValueAsString(receipt),user);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Work order receipt serialization failed",e);}
        audit.append(new com.platform.audit.AuditEvidence("CARBON",building,"USER",user,"EXECUTE_CONFIGURATION_COMMAND",
                "CARBON_CONFIGURATION_COMMAND",id,version,null,null,"domain="+receipt.domain()+";action="+receipt.action()+";reference="+receipt.reference()+";guide="+order.fingerprint()+";requestHash="+hash,
                "SUCCESS",null,com.platform.audit.TraceContext.current(),java.time.LocalDateTime.now(),auditProperties.getEnvironmentMode(),false));
        return receipt;
    }
    private String hash(Object command) {try{return com.platform.energy.period.EnergyPeriodCalculationService.digest(mapper.writeValueAsString(command));}catch(Exception e){throw new IllegalStateException(e);}}
    private Receipt apply(long user,Collection<String> roles,String building,String version,Command command,WorkOrder order) {
        var step=order.guide().steps().stream().filter(s->s.domain().equals(command.domain())).findFirst()
                .orElseThrow(()->error(400,VALIDATION_FAILED,"工作单步骤不存在"));
        Set<String> allowed=switch(step.action()) {
            case "SUBMIT"->Set.of("SUBMIT");case "REVIEW"->Set.of("APPROVE","REJECT","WITHDRAW");case "ACTIVATE"->Set.of("ACTIVATE");default->Set.of();
        };
        if(step.reference()==null||step.revision()==null||!allowed.contains(command.action()))throw error(409,STATUS_CONFLICT,"该步骤当前不可执行所选操作，请先处理待确认项");
        String key="work-order:"+command.idempotencyKey();
        if(step.domain().equals("PROJECT")) {
            var request=new CarbonProjectContracts.Transition(step.revision(),command.reason(),command.effectiveFrom());
            var result=command.action().equals("ACTIVATE")?projects.activate(user,roles,building,version,request)
                    :projects.transition(user,roles,building,version,command.action().toLowerCase(Locale.ROOT),request);
            return new Receipt(building,version,step.domain(),command.action(),result.versionId(),result.status(),result.revision());
        }
        if(step.domain().equals("CLASSIFICATION")) {
            var request=new ClassificationContracts.TransitionRequest(step.revision(),command.reason(),command.effectiveFrom());
            var result=switch(command.action()) {
                case "SUBMIT"->classifications.submit(user,roles,building,step.reference(),request);
                case "APPROVE"->classifications.approve(user,roles,building,step.reference(),request);
                case "REJECT","WITHDRAW"->classifications.reopen(user,roles,building,step.reference(),request,command.action().equals("REJECT"));
                default->throw error(400,VALIDATION_FAILED,"分类操作无效");
            };
            return new Receipt(building,version,step.domain(),command.action(),result.versionId(),result.status(),result.revision());
        }
        if(!step.domain().equals("RELATION"))throw error(409,STATUS_CONFLICT,"该步骤只能进入原配置页面处理");
        switch(command.action()) {
            case "SUBMIT"->relations.submit(user,roles,step.reference(),key,new RelationContracts.RevisionReasonRequest(step.revision(),command.reason()));
            case "WITHDRAW"->relations.withdraw(user,roles,step.reference(),new RelationContracts.RevisionReasonRequest(step.revision(),command.reason()));
            case "ACTIVATE"->relations.activate(user,roles,step.reference(),key,new RelationContracts.ActivationRequest(relations.model(user,roles,building).modelRevision(),command.reason()));
            case "APPROVE","REJECT"->{
                var review=relations.reviews(user,roles,building).stream().filter(r->r.versionId().equals(step.reference())&&r.status().equals("PENDING")).findFirst()
                        .orElseThrow(()->error(409,VERSION_CONFLICT,"关系审核请求已变化"));
                var request=new RelationContracts.ReviewDecisionRequest(command.reason());
                if(command.action().equals("APPROVE"))relations.approve(user,roles,review.requestId(),key,request);
                else relations.reject(user,roles,review.requestId(),key,request);
            }
            default->throw error(400,VALIDATION_FAILED,"关系操作无效");
        }
        var result=relations.versionDetail(user,roles,building,step.reference()).version();
        return new Receipt(building,version,step.domain(),command.action(),result.versionId(),result.status(),result.revision());
    }
}
