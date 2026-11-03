package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.platform.carbon.CarbonPlanContracts.Month;
import static com.platform.carbon.CarbonPlanContracts.*;
import static com.platform.carbon.CarbonErrors.*;

/** 冻结年度基准与治理版本；实际值仅查询有效正式月结果，任何读取均不触发封账或核算。 */
@Service
@Transactional(readOnly=true)
public class CarbonPlanService {
    private final CarbonPlanRepository repository;
    private final CarbonCalculationRepository carbon;
    private final CarbonAuthorization authorization;
    private final CarbonPlanCore core;
    private final ObjectMapper json;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public CarbonPlanService(CarbonPlanRepository repository,CarbonCalculationRepository carbon,
            CarbonAuthorization authorization,CarbonPlanCore core,ObjectMapper json,
            AuditEvidenceWriter audit,AuditGovernanceProperties auditProperties) {
        this(repository,carbon,authorization,core,json,audit,auditProperties,Clock.systemUTC());
    }
    CarbonPlanService(CarbonPlanRepository repository,CarbonCalculationRepository carbon,
            CarbonAuthorization authorization,CarbonPlanCore core,ObjectMapper json,
            AuditEvidenceWriter audit,AuditGovernanceProperties auditProperties,Clock clock) {
        this.repository=repository;this.carbon=carbon;this.authorization=authorization;this.core=core;
        this.json=json;this.audit=audit;this.auditProperties=auditProperties;this.clock=clock;
    }
    public List<Version> versions(long user,Collection<String> roles,String building,int year) {
        read(user,roles,building);year(year);return rows(building,year).stream().map(this::view).toList();
    }
    public Baseline baselineYear(long user,Collection<String> roles,String building,int sourceYear) {
        read(user,roles,building);year(sourceYear);
        if(sourceYear>=today().getYear()) throw error(409,ACTIVITY_INCOMPLETE,"基准年须为已经结束的自然年");
        List<BaselineMonth> months=new ArrayList<>();
        for(int m=1;m<=12;m++) {
            Month actual=actual(building,sourceYear,m);
            if(!"AVAILABLE".equals(actual.actualStatus())) throw error(409,ACTIVITY_INCOMPLETE,"基准年"+m+"月缺少完整有效正式碳结果："+actual.actualStatus());
            months.add(new BaselineMonth(m,actual.actual(),CarbonPlanCore.UNIT,"正式发布月度核算",actual.batchId(),actual.evidenceHash(),actual.referenceVersionId()));
        }
        return core.normalize(new Baseline("FORMAL_YEAR",sourceYear,CarbonPlanCore.ZONE,"建筑范围一、二 TOTAL_EMISSION；各月边界按来源批次冻结证据追溯","平台有效正式月度核算",months,null,null),building);
    }
    /** 文件预览不落版本；正式保存再次经过同一业务校验与独立审核。 */
    public void importedAccess(long user,Collection<String> roles,String building) { maintain(user,roles,building); }
    public Baseline imported(long user,Collection<String> roles,String building,Baseline baseline) {
        read(user,roles,building);authorization.requireRuleMaintainer(user,roles,building);
        if(baseline==null || "FORMAL_YEAR".equals(baseline.nature())) CarbonPlanCore.invalid("导入只接受外部确认资料或明确测试夹具");
        testNature(baseline);return core.normalize(baseline,building);
    }
    @Transactional(rollbackFor=Exception.class)
    public Version create(long user,Collection<String> roles,String building,Create request) {
        maintain(user,roles,building);year(request.year());reason(request.reason());repository.lock(building,request.year());
        var current=repository.versionsForUpdate(building,request.year());
        if(current.size()>100) throw error(409,LIMIT_EXCEEDED,"年度计划版本超过100条");
        if(current.stream().anyMatch(r->Set.of("DRAFT","PENDING_REVIEW","APPROVED").contains(r.status()))) conflict();
        String configuration=null;
        if(request.copiedFromVersionId()!=null) {
            var old=require(building,request.copiedFromVersionId());
            if(old.year()!=request.year() || !Set.of("ACTIVE","RETIRED").contains(old.status())) CarbonPlanCore.invalid("修订须引用本建筑同年度已生效版本");
            configuration=old.json();
        }
        String id=UUID.randomUUID().toString().replace("-","");
        repository.insert(id,building,request.year(),configuration,request.copiedFromVersionId(),user,request.reason());
        log(user,building,"CREATE",id,request.reason(),false);return view(require(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public Version save(long user,Collection<String> roles,String building,String id,Save request) {
        maintain(user,roles,building);reason(request.reason());var row=lockVersion(building,id);author(row,user);
        if(row.revision()!=request.expectedRevision()) conflict();
        Configuration config=core.normalize(request.configuration(),building);testNature(config.baseline());
        if("FORMAL_YEAR".equals(config.baseline().nature())) {
            // 来自浏览器的批次和值不能充当正式来源。允许原已冻结快照原样修订，否则重读并核对预览。
            var previous=decode(row);
            boolean unchanged=previous!=null && previous.baseline().equals(config.baseline());
            if(!unchanged && !baselineYear(user,roles,building,config.baseline().sourceYear()).equals(config.baseline()))
                throw error(409,VERSION_CONFLICT,"基准年来源已变化或预览内容无效，请重新预览");
        }
        if(repository.save(row,request.expectedRevision(),encode(config),request.reason())!=1) conflict();
        log(user,building,"SAVE",id,request.reason(),false);return view(require(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public Version transition(long user,Collection<String> roles,String building,String id,String action,Transition request) {
        read(user,roles,building);reason(request.reason());var row=lockVersion(building,id);
        // 把待校验内容绑定到调用方修订号，拒绝拿旧快照校验后以猜测的更新修订号执行CAS。
        if(row.revision()!=request.expectedRevision()) conflict();
        String from,to;Long reviewer=row.reviewer();
        switch(action) {
            case "submit" -> { authorization.requireRuleMaintainer(user,roles,building);author(row,user);from="DRAFT";to="PENDING_REVIEW";ready(row); }
            case "withdraw" -> { authorization.requireRuleMaintainer(user,roles,building);author(row,user);from="PENDING_REVIEW";to="DRAFT";reviewer=null; }
            case "approve","reject" -> {
                authorization.requireRuleReviewer(user,roles,building);authorization.requireSeparation(row.author(),user);
                from="PENDING_REVIEW";to=action.equals("approve")?"APPROVED":"DRAFT";reviewer=action.equals("approve")?user:null;
                if(action.equals("approve")) ready(row);
            }
            case "activate" -> {
                authorization.requireRuleActivator(user,roles,building);ready(row);
                if(repository.activate(row,request.expectedRevision(),user,request.reason())!=1) conflict();
                log(user,building,"ACTIVATE",id,request.reason(),false);return view(require(building,id));
            }
            default -> throw error(400,VALIDATION_FAILED,"计划操作无效");
        }
        if(repository.transition(row,request.expectedRevision(),from,to,reviewer,request.reason())!=1) conflict();
        // 复用既有TEST自审例外时如实标记，不能把例外记录成独立审核。
        boolean selfApproval=Set.of("approve","reject").contains(action) && row.author()==user && auditProperties.isAllowSelfApproval();
        log(user,building,action.toUpperCase(Locale.ROOT),id,request.reason(),selfApproval);return view(require(building,id));
    }
    public Response query(long user,Collection<String> roles,String building,int year,String throughMonth) {
        read(user,roles,building);year(year);
        YearMonth now=YearMonth.from(today());
        int completed=year<now.getYear()?12:year==now.getYear()?now.getMonthValue()-1:0;
        int through=completed;
        if(throughMonth!=null) {
            try {
                if(!throughMonth.matches("\\d{4}-\\d{2}")) throw new DateTimeException("format");
                var requested=YearMonth.parse(throughMonth);
                if(requested.getYear()!=year) throw new DateTimeException("year");
                through=requested.getMonthValue();
            } catch(DateTimeException exception) { throw error(400,VALIDATION_FAILED,"截止月须为计划年度内的YYYY-MM"); }
        }
        var row=repository.active(building,year);Version plan=row==null?null:view(row);
        Configuration config=plan==null?null:plan.configuration();
        if(plan!=null && config==null) throw error(409,DEPENDENCY_UNAVAILABLE,"已生效计划缺少基准");
        if(config!=null) testNature(config.baseline());
        List<Month> months=new ArrayList<>();List<Integer> missing=new ArrayList<>();BigDecimal sum=BigDecimal.ZERO;
        for(int m=1;m<=12;m++) {
            Month actual=actual(building,year,m);
            BigDecimal baseline=config==null?null:config.baseline().months().get(m-1).value();
            months.add(new Month(m,baseline,baseline==null?null:core.quota(baseline,config.reductionPercent()),actual.actual(),actual.actualStatus(),actual.batchId(),actual.evidenceHash(),actual.referenceVersionId(),actual.startInclusive(),actual.endExclusive()));
            if(m<=through) {
                if(!"AVAILABLE".equals(actual.actualStatus())) missing.add(m);
                else sum=sum.add(actual.actual());
            }
        }
        String status=through==0?"NO_COMPLETED_MONTH":through>completed?"PERIOD_NOT_ENDED":!missing.isEmpty()?"INCOMPLETE_ACTUAL":"COMPLETE";
        BigDecimal cumulative="COMPLETE".equals(status)?sum:null;
        BigDecimal annual=config==null?null:config.baseline().annualBaseline();
        BigDecimal quota=config==null?null:core.quota(annual,config.reductionPercent());
        return new Response(building,year,CarbonPlanCore.ZONE,CarbonPlanCore.UNIT,plan==null?"NO_ACTIVE_PLAN":status,
                through==0?null:YearMonth.of(year,through).toString(),completed==0?null:YearMonth.of(year,completed).toString(),List.copyOf(missing),plan,annual,config==null?null:config.reductionPercent(),quota,cumulative,
                quota==null||cumulative==null?null:quota.subtract(cumulative),List.copyOf(months));
    }
    private Month actual(String building,int year,int month) {
        var date=YearMonth.of(year,month);var zone=ZoneId.of(CarbonPlanCore.ZONE);
        Instant start=date.atDay(1).atStartOfDay(zone).toInstant(),end=date.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        if(date.compareTo(YearMonth.from(today()))>=0) return new Month(month,null,null,null,"PERIOD_NOT_ENDED",null,null,null,start,end);
        var batch=carbon.findCurrentFormal(building,CarbonModels.PeriodType.MONTH,start,end,CarbonPlanCore.ZONE);
        if(batch==null) return new Month(month,null,null,null,"NO_FORMAL_RESULT",null,null,null,start,end);
        var metrics=carbon.listSummaries(batch.batchId()).stream().filter(m->"TOTAL_EMISSION".equals(m.metricCode()) && "TOTAL".equals(m.dimensionCode())).toList();
        if(metrics.size()!=1) return new Month(month,null,null,null,"MISSING_OR_CONFLICTING_METRIC",batch.batchId(),null,batch.roundingPolicyVersionId(),start,end);
        var metric=metrics.getFirst();
        boolean valid=CarbonPlanCore.UNIT.equals(metric.unitCode()) && metric.finalValue()!=null && metric.finalValue().signum()>=0 && metric.unavailableReason()==null && metric.evidenceHash()!=null && !metric.evidenceHash().isBlank();
        return new Month(month,null,null,valid?metric.finalValue():null,valid?"AVAILABLE":"METRIC_UNAVAILABLE",batch.batchId(),metric.evidenceHash(),batch.roundingPolicyVersionId(),start,end);
    }
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of(CarbonPlanCore.ZONE))); }
    private void read(long user,Collection<String> roles,String building) {
        CarbonPlanCore.text(building,32,"建筑标识");authorization.requireReader(user,roles,building);
        if(!repository.buildingExists(building)) throw error(404,NOT_FOUND,"建筑不存在");
    }
    private void maintain(long user,Collection<String> roles,String building) { read(user,roles,building);authorization.requireRuleMaintainer(user,roles,building); }
    private static void year(int year) { if(year<2000 || year>2200) CarbonPlanCore.invalid("年度须在2000至2200之间"); }
    private static void reason(String reason) { CarbonPlanCore.text(reason,500,"操作原因"); }
    private static void author(CarbonPlanRepository.Row row,long user) { if(row.author()!=user) throw error(403,FORBIDDEN,"只能修改本人草稿"); }
    private static void conflict() { throw error(409,VERSION_CONFLICT,"计划状态或修订已变化"); }
    private void testNature(Baseline b) {
        if("TEST_FIXTURE".equals(b.nature()) && auditProperties.getEnvironmentMode()!=AuditEnvironmentMode.TEST) throw error(400,VALIDATION_FAILED,"测试基准只允许在TEST环境配置");
    }
    private List<CarbonPlanRepository.Row> rows(String building,int year) {
        var values=repository.versions(building,year);if(values.size()>100) throw error(409,LIMIT_EXCEEDED,"年度计划版本超过100条");return values;
    }
    private CarbonPlanRepository.Row require(String building,String id) {
        var row=repository.find(building,id);if(row==null) throw error(404,NOT_FOUND,"计划版本不存在");return row;
    }
    private CarbonPlanRepository.Row lockVersion(String building,String id) {
        var identity=require(building,id);repository.lock(building,identity.year());
        var row=repository.findForUpdate(building,id);
        if(row==null) throw error(404,NOT_FOUND,"计划版本不存在");
        return row;
    }
    private Configuration decode(CarbonPlanRepository.Row row) {
        if(row.json()==null) return null;
        try { return core.normalize(json.readValue(row.json(),Configuration.class),row.building()); }
        catch(Exception exception) { throw error(409,DEPENDENCY_UNAVAILABLE,"计划存储内容无效"); }
    }
    private void ready(CarbonPlanRepository.Row row) {
        var config=decode(row);if(config==null) throw error(409,DEPENDENCY_UNAVAILABLE,"请先保存完整年度计划");testNature(config.baseline());
    }
    private Version view(CarbonPlanRepository.Row row) {
        return new Version(row.id(),row.building(),row.year(),row.number(),row.revision(),row.status(),row.author(),row.reviewer(),row.activator(),row.activatedAt()==null?null:Instant.ofEpochMilli(row.activatedAt()),row.copiedFrom(),decode(row));
    }
    private String encode(Configuration c) { try { return json.writeValueAsString(c); } catch(Exception exception) { throw error(400,VALIDATION_FAILED,"计划无法序列化"); } }
    private void log(long user,String building,String action,String id,String reason,boolean selfApproval) {
        audit.append(new AuditEvidence("CARBON_MANAGEMENT",building,"USER",user,"CARBON_PLAN_"+action,"CARBON_PLAN",id,id,null,null,reason,"SUCCESS",null,TraceContext.current(),LocalDateTime.now(),auditProperties.getEnvironmentMode(),selfApproval));
    }
}
