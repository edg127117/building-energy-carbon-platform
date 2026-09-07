package com.platform.energy.efficiency;

import com.platform.audit.*;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.energy.period.NativePeriodSnapshotService;
import com.platform.energy.period.NativePeriodSnapshotService.*;
import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.platform.energy.efficiency.EerpContracts.*;
import static com.platform.energy.efficiency.EerpRepository.*;
import static com.platform.energy.efficiency.EerpSupport.*;
import static com.platform.energy.efficiency.EerpPeriodCalculator.Execution;

/** 冷站配置审核与有界任务编排；数值发布不持有业务事务，固定证据后才允许重试。 */
@Service
@lombok.extern.slf4j.Slf4j
public class EerpService {
    private final EerpRepository repo;
    private final EerpSupport codec;
    private final EerpLimits limits;
    private final EnergyPeriodAuthorization auth;
    private final EerpConfigurationValidator validator;
    private final EerpPeriodCalculator calculator;
    private final NativePeriodSnapshotService snapshots;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;
    private final TransactionTemplate tx;
    private final java.util.concurrent.Semaphore executionSlots;

    public EerpService(EerpRepository repo,EerpSupport codec,EerpLimits limits,EnergyPeriodAuthorization auth,
            EerpConfigurationValidator validator,EerpPeriodCalculator calculator,NativePeriodSnapshotService snapshots,
            AuditEvidenceWriter audit,AuditGovernanceProperties auditProperties,PlatformTransactionManager manager) {
        this.repo=repo; this.codec=codec; this.limits=limits; this.auth=auth; this.validator=validator;
        this.calculator=calculator; this.snapshots=snapshots; this.audit=audit; this.auditProperties=auditProperties;
        this.tx=new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.executionSlots=new java.util.concurrent.Semaphore(limits.getMaximumConcurrentTasks());
    }
    public ConfigView createConfig(long user,Collection<String> roles,Configuration configuration) {
        auth.requirePolicyMaintainer(user,roles);
        require(configuration!=null,"INVALID_REQUEST","配置不能为空"); auth.checkBuilding(user,roles,configuration.buildingId());
        String evidence=validator.validate(user,roles,configuration); checkSize(evidence);
        return tx.execute(t -> {
            repo.stationGuard(configuration.buildingId(),configuration.stationId());
            ConfigRow row=new ConfigRow(id(),configuration.buildingId(),configuration.stationId(),"DRAFT",0,
                    configuration.effectiveFrom(),configuration.effectiveTo(),codec.json(configuration),evidence,user,null,null);
            repo.insertConfig(row); audit(user,row.building(),row.id(),"CREATE_CONFIG","DRAFT",null,false);
            return configView(repo.config(row.id()));
        });
    }
    public List<ConfigView> configs(long user,Collection<String> roles,String building,String station,int limit) {
        read(user,roles,building); require(limit>0&&limit<=100,"CAPACITY_EXCEEDED","单页上限100");
        return repo.configs(building,station,limit).stream().map(this::configView).toList();
    }
    public ConfigView config(long user,Collection<String> roles,String id) {
        var row=requireConfig(id); read(user,roles,row.building()); return configView(row);
    }
    public ConfigView reviewConfig(long user,Collection<String> roles,String id,String action,Review review) {
        require(review!=null,"INVALID_REQUEST","审核请求不能为空"); text(review.reason(),500,"审核理由");
        if ("SUBMIT".equals(action)) auth.requirePolicyMaintainer(user,roles); else auth.requirePolicyReviewer(user,roles);
        return tx.execute(t -> {
            var row=requireConfig(id); auth.checkBuilding(user,roles,row.building()); repo.stationGuard(row.building(),row.station());
            String before,after;
            switch(action) {
                case "SUBMIT" -> { before="DRAFT"; after="SUBMITTED"; }
                case "APPROVE" -> { before="SUBMITTED"; after="APPROVED"; auth.requireSeparation(row.submitter()==null?row.creator():row.submitter(),user); }
                case "REJECT" -> { before="SUBMITTED"; after="REJECTED"; auth.requireSeparation(row.submitter()==null?row.creator():row.submitter(),user); }
                case "ACTIVATE" -> {
                    before="APPROVED"; after="ACTIVE";
                    if (repo.configOverlap(row)) throw error(409,"CONFIG_OVERLAP","已生效配置与该版本时间重叠");
                }
                default -> throw error(400,"INVALID_ACTION","配置动作无效");
            }
            if ("APPROVE".equals(action) || "ACTIVATE".equals(action)) {
                // 再核对引用，避免草稿创建与批准之间档案变更被审核绕过；原证据不原地覆盖。
                validator.validate(user,roles,codec.read(row.json(),Configuration.class));
            }
            conflict(repo.transitionConfig(id,review.expectedRevision(),before,after,user,review.reason()));
            boolean self=("APPROVE".equals(action)||"REJECT".equals(action))&&Objects.equals(row.submitter(),user);
            audit(user,row.building(),id,action+"_CONFIG",after+";reason="+review.reason(),null,self);
            return configView(requireConfig(id));
        });
    }
    public TaskView createPeriod(long user,Collection<String> roles,PeriodRequest request) {
        auth.requireCalculation(user,roles); require(request!=null,"INVALID_REQUEST","计算请求不能为空");
        ConfigRow config=requireConfig(request.configVersionId()); auth.checkBuilding(user,roles,config.building());
        validatePeriod(request,config);
        TaskRow task=createTask(user,roles,config.building(),config.station(),"PERIOD",request.idempotencyKey(),request,request.predecessorTaskId());
        return "PENDING_RECALC".equals(task.status()) ? view(task) : execute(user,roles,task.id());
    }
    public TaskView createAnnual(long user,Collection<String> roles,AnnualRequest request) {
        auth.requireCalculation(user,roles); validateAnnual(request); auth.checkBuilding(user,roles,request.buildingId());
        TaskRow task=createTask(user,roles,request.buildingId(),request.stationId(),"ANNUAL",request.idempotencyKey(),request,request.predecessorTaskId());
        return "PENDING_RECALC".equals(task.status()) ? view(task) : execute(user,roles,task.id());
    }
    private TaskRow createTask(long user,Collection<String> roles,String building,String station,String kind,String key,Object request,String predecessor) {
        text(key,64,"幂等键"); String json=codec.json(request); checkSize(json);
        if (predecessor!=null) auth.requireRecalculationSubmitter(user,roles);
        return tx.execute(t -> {
            repo.stationGuard(building,station);
            var prior=repo.byKey(building,station,key);
            if (prior!=null) {
                if (!hash(json).equals(prior.requestHash())||!kind.equals(prior.kind())) throw error(409,"IDEMPOTENCY_CONFLICT","相同幂等键的请求摘要不同");
                return prior;
            }
            if (predecessor!=null) validatePredecessor(requireTask(predecessor),building,station,kind,request);
            var row=new TaskRow(id(),building,station,kind,key,hash(json),json,predecessor,
                    predecessor==null?"READY":"PENDING_RECALC",0,user,null,null,null,null,null,null,null,Instant.now(),TraceContext.current());
            repo.insertTask(row); audit(user,building,row.id(),"CREATE_TASK",row.status(),predecessor,false); return requireTask(row.id());
        });
    }
    public TaskView reviewTask(long user,Collection<String> roles,String id,String action,Review review) {
        require(review!=null,"INVALID_REQUEST","审核请求不能为空"); text(review.reason(),500,"审核理由");
        if ("APPROVE_RECALC".equals(action)) auth.requireRecalculationReviewer(user,roles);
        else if ("SUBMIT_SEAL".equals(action)) auth.requireLockSubmitter(user,roles);
        else auth.requireLockReviewer(user,roles);
        return tx.execute(t -> {
            TaskRow row=requireTask(id); auth.checkBuilding(user,roles,row.building());
            String before,after;
            switch(action) {
                case "APPROVE_RECALC" -> { before="PENDING_RECALC"; after="READY"; auth.requireSeparation(row.creator(),user); }
                case "SUBMIT_SEAL" -> { before="SUCCEEDED"; after="PENDING_SEAL"; }
                case "APPROVE_SEAL" -> { before="PENDING_SEAL"; after="SEALED"; auth.requireSeparation(row.submitter()==null?row.creator():row.submitter(),user); }
                default -> throw error(400,"INVALID_ACTION","任务审核动作无效");
            }
            conflict(repo.reviewTask(id,review.expectedRevision(),before,after,user,review.reason()));
            boolean self = "APPROVE_RECALC".equals(action) ? row.creator()==user
                    : "APPROVE_SEAL".equals(action) && Objects.equals(row.submitter(),user);
            audit(user,row.building(),id,action,after+";reason="+review.reason(),row.predecessor(),self); return view(requireTask(id));
        });
    }
    public TaskView execute(long user,Collection<String> roles,String id) {
        auth.requireCalculation(user,roles); var initial=requireTask(id); auth.checkBuilding(user,roles,initial.building());
        if (Set.of("SUCCEEDED","PENDING_SEAL","SEALED").contains(initial.status())) return view(initial);
        // 数据库租约过期不代表旧依赖调用已返回；本机槽位同时限制实际存活的执行线程。
        if(!executionSlots.tryAcquire()) throw error(409,"CAPACITY_EXCEEDED","本机执行槽位已满");
        try {
        String token=EerpSupport.id(); Instant deadline=Instant.now().plusSeconds(limits.getExecutionTimeoutSeconds());
        TaskRow claimed=tx.execute(t -> {
            repo.executionGuard(); var row=requireTask(id);
            if (repo.running(Instant.now())>=limits.getMaximumConcurrentTasks()) throw error(409,"CAPACITY_EXCEEDED","并发计算已满");
            conflict(repo.claim(id,row.revision(),token,deadline,Instant.now()));
            audit(user,row.building(),id,"CLAIM_TASK","RUNNING",row.predecessor(),false); return requireTask(id);
        });
        Runnable budget=() -> { if (!Instant.now().isBefore(deadline)) throw error(409,"EXECUTION_TIMEOUT","任务超过执行时间预算，可显式恢复"); };
        try {
            Execution execution;
            if (claimed.stage()!=null) execution=codec.read(claimed.stage(),Execution.class);
            else {
                if ("PERIOD".equals(claimed.kind())) {
                    PeriodRequest request=codec.read(claimed.request(),PeriodRequest.class); var config=requireConfig(request.configVersionId());
                    validatePeriod(request,config);
                    execution=calculator.calculate(user,roles,id,configView(config),request,budget);
                } else execution=annualExecution(user,roles,claimed,budget);
                budget.run(); String stage=codec.json(execution); checkSize(stage);
                tx.executeWithoutResult(t -> conflict(repo.stage(id,token,stage,hash(stage))));
            }
            // 重试读取已固定 stage，不重新拉取事实；部分 TDengine 成功只会重复同内容的幂等写入。
            for (Snapshot snapshot:execution.numericSnapshots()) { budget.run(); snapshots.publish(user,roles,snapshot); }
            budget.run();
            tx.executeWithoutResult(t -> { conflict(repo.finish(id,token,"SUCCEEDED",null)); audit(user,claimed.building(),id,"COMPLETE_TASK","SUCCEEDED",claimed.predecessor(),false); });
        } catch (RuntimeException failure) {
            log.warn("EERp task {} failed; immutable staged evidence is retained",id,failure);
            String code=failure instanceof BusinessException b && b.getErrorCode()!=null ? b.getErrorCode() : "EERP_EXECUTION_FAILED";
            tx.executeWithoutResult(t -> { if(repo.finish(id,token,"FAILED",code)==1) audit(user,claimed.building(),id,"FAIL_TASK",code,claimed.predecessor(),false); });
        }
        return view(requireTask(id));
        } finally { executionSlots.release(); }
    }
    private Execution annualExecution(long user,Collection<String> roles,TaskRow task,Runnable budget) {
        AnnualRequest request=codec.read(task.request(),AnnualRequest.class); validateAnnual(request);
        List<EerpAnnualCore.Segment> segments=new ArrayList<>();
        Map<String,Object> evidence=new LinkedHashMap<>();
        for(String id:request.periodTaskIds()) {
            budget.run(); TaskRow period=requireTask(id); auth.checkBuilding(user,roles,period.building());
            require("PERIOD".equals(period.kind())&&"SEALED".equals(period.status()),"INPUT_NOT_SEALED","年度输入必须为已审核周期快照");
            Execution saved=codec.read(period.stage(),Execution.class); PeriodResult result=codec.read(saved.resultJson(),PeriodResult.class);
            require(request.buildingId().equals(result.buildingId())&&request.stationId().equals(result.stationId()),"PERIOD_MISMATCH","周期不属于请求冷站");
            Configuration config=codec.read(requireConfig(result.configVersionId()).json(),Configuration.class);
            for (Measure measure:result.measures()) if(measure.numericSnapshotId()!=null) {
                var published=snapshots.read(user,roles,result.buildingId(),measure.numericSnapshotId());
                require(published.samples().size()==1&&published.samples().getFirst().value().compareTo(measure.quantityKwh())==0,
                        "EVIDENCE_MISMATCH","原生量快照与周期索引不一致");
            }
            segments.add(new EerpAnnualCore.Segment(id,result,config));
            // 原周期 stage 不可覆盖；年度保存精确索引与摘要，追溯接口可继续读取该原始输入。
            evidence.put(id,Map.of("evidenceHash",period.evidenceHash(),"result",result,"configVersionId",result.configVersionId()));
        }
        AnnualResult result=new EerpAnnualCore().calculate(request,segments,Instant.now());
        List<Snapshot> numeric=new ArrayList<>();
        String evidenceJson=codec.json(evidence);
        if(result.eerp()!=null) numeric.add(new Snapshot(task.id()+"ratio",task.building(),task.station(),"EERP","1",hash(evidenceJson),
                List.of(new NumericSample(result.fromInclusive(),result.eerp(),BigDecimal.ONE))));
        return new Execution(codec.json(result),evidenceJson,List.copyOf(numeric));
    }
    public TaskView task(long user,Collection<String> roles,String id) { var row=requireTask(id);read(user,roles,row.building());return view(row); }
    public List<TaskView> tasks(long user,Collection<String> roles,String building,String station,int limit) {
        read(user,roles,building);require(limit>0&&limit<=100,"CAPACITY_EXCEEDED","单页上限100");return repo.tasks(building,station,limit).stream().map(this::view).toList();
    }
    public TraceView trace(long user,Collection<String> roles,String id) {
        var row=requireTask(id);read(user,roles,row.building());
        return new TraceView(view(row),row.request(),row.stage()==null?null:codec.read(row.stage(),Execution.class).evidenceJson());
    }
    private void validatePeriod(PeriodRequest r,ConfigRow c) {
        require("ACTIVE".equals(c.status()),"CONFIG_NOT_ACTIVE","配置版本尚未生效");
        millis(r.fromInclusive());millis(r.toExclusive());millis(r.asOf());
        require(r.fromInclusive().isBefore(r.toExclusive())&&!r.fromInclusive().isBefore(c.from())&&!r.toExclusive().isAfter(c.to()),"INVALID_INTERVAL","计算区间跨越配置生效边界");
        require(Duration.between(r.fromInclusive(),r.toExclusive()).toMillis()<=limits.getMaximumPeriodSeconds()*1000,"CAPACITY_EXCEEDED","周期任务跨度超过上限");
        require(!r.asOf().isBefore(r.toExclusive())&&!r.asOf().isAfter(Instant.now()),"INVALID_WATERMARK","水位须覆盖计算周期且不得在未来");
    }
    private void validateAnnual(AnnualRequest r) {
        require(r!=null,"INVALID_REQUEST","年度请求不能为空");text(r.buildingId(),32,"建筑");text(r.stationId(),32,"冷站");text(r.timezoneVersion(),64,"时区版本");
        require(r.year()>=1970&&r.year()<=9998,"INVALID_YEAR","年份无效");
        try { ZoneId.of(r.timezoneId()); }catch(RuntimeException e){throw error(400,"INVALID_TIMEZONE","时区无效");}
        require(r.periodTaskIds()!=null&&r.periodTaskIds().size()<=limits.getMaximumAnnualSegments(),"CAPACITY_EXCEEDED","年度快照数量超过预算");
        require(new HashSet<>(r.periodTaskIds()).size()==r.periodTaskIds().size(),"PERIOD_OVERLAP","年度快照重复");
        for(String id:r.periodTaskIds())text(id,32,"周期任务");
    }
    private void validatePredecessor(TaskRow p,String building,String station,String kind,Object request) {
        require(p.building().equals(building)&&p.station().equals(station)&&p.kind().equals(kind)
                &&Set.of("SUCCEEDED","PENDING_SEAL","SEALED").contains(p.status()),"PREDECESSOR_MISMATCH","重算前序必须为同冷站已完成结果");
        if(request instanceof PeriodRequest r) {
            var old=codec.read(p.request(),PeriodRequest.class);
            require(old.fromInclusive().equals(r.fromInclusive())&&old.toExclusive().equals(r.toExclusive()),"PREDECESSOR_MISMATCH","重算必须保持前序周期边界");
        }else if(request instanceof AnnualRequest r){
            var old=codec.read(p.request(),AnnualRequest.class);
            require(old.year()==r.year()&&old.timezoneId().equals(r.timezoneId())&&old.timezoneVersion().equals(r.timezoneVersion()),"PREDECESSOR_MISMATCH","重算必须保持年度边界");
        }
    }
    private ConfigView configView(ConfigRow r) {return new ConfigView(r.id(),r.revision(),r.status(),r.creator(),r.submitter(),r.reviewer(),codec.read(r.json(),Configuration.class),r.relationEvidence());}
    private TaskView view(TaskRow r) {
        Object result=null;
        if(r.stage()!=null&&Set.of("SUCCEEDED","PENDING_SEAL","SEALED").contains(r.status())) {
            Execution stage=codec.read(r.stage(),Execution.class);
            result="PERIOD".equals(r.kind())?codec.read(stage.resultJson(),PeriodResult.class):codec.read(stage.resultJson(),AnnualResult.class);
        }
        return new TaskView(r.id(),r.kind(),r.building(),r.station(),r.status(),r.revision(),r.creator(),r.submitter(),r.reviewer(),r.predecessor(),r.createdAt(),r.traceId(),r.failure(),r.evidenceHash(),result);
    }
    private ConfigRow requireConfig(String id){text(id,32,"配置版本");var r=repo.config(id);if(r==null)throw error(404,"NOT_FOUND","配置版本不存在");return r;}
    private TaskRow requireTask(String id){text(id,32,"任务身份");var r=repo.task(id);if(r==null)throw error(404,"NOT_FOUND","计算任务不存在");return r;}
    private void read(long user,Collection<String> roles,String building){auth.requireReader(roles);auth.checkBuilding(user,roles,building);}
    private static void conflict(int rows){if(rows!=1)throw error(409,"REVISION_CONFLICT","版本、执行租约或审核状态已变化");}
    private void checkSize(String evidence){require(evidence.getBytes(StandardCharsets.UTF_8).length<=limits.getMaximumEvidenceBytes(),"CAPACITY_EXCEEDED","固定证据超过任务存储预算");}
    private void audit(long user,String building,String id,String action,String status,String predecessor,boolean self){
        boolean failed="FAIL_TASK".equals(action);
        audit.append(new AuditEvidence("ENERGY_EERP",building,"USER",user,action,"EERP",id,id,predecessor,null,status,failed?"FAILED":"SUCCESS",failed?status:null,
                TraceContext.current(),LocalDateTime.now(),auditProperties.getEnvironmentMode(),self));
    }
}
